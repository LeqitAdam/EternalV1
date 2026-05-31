package de.eternal.api;

import de.eternal.core.model.LinkCode;
import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.model.PunishmentType;
import de.eternal.core.model.ReportEntry;
import de.eternal.core.model.Session;
import de.eternal.core.model.UnbanAppeal;
import de.eternal.core.storage.EternalStorage;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.NotFoundResponse;
import org.jetbrains.annotations.NotNull;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Routes {

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final EternalStorage storage;
    private final Auth auth;
    private final ApiConfig config;
    private final de.eternal.core.config.ReasonsConfig reasons;

    public Routes(@NotNull EternalStorage storage, @NotNull Auth auth, @NotNull ApiConfig config,
                  @NotNull de.eternal.core.config.ReasonsConfig reasons) {
        this.storage = storage;
        this.auth = auth;
        this.config = config;
        this.reasons = reasons;
    }

    public void register(@NotNull Javalin app) {
        // --- public --------------------------------------------------------
        app.get("/health", ctx -> ctx.json(Map.of("status", "ok", "ts", Instant.now().toEpochMilli())));
        app.post("/auth/link/init", this::linkInit);
        app.get("/auth/link/status/{token}", this::linkStatus);
        app.post("/auth/logout", this::logout);
        app.post("/public/appeals", this::publicAppeal);

        // --- plugin -> api (X-Internal-Secret) -----------------------------
        app.post("/auth/link/confirm", this::linkConfirm);
        app.get("/actions/pending", this::pendingActions);
        app.post("/actions/{id}/consumed", this::consumeAction);

        // --- authenticated (bearer) ----------------------------------------
        app.get("/me", this::me);
        app.get("/me/punishments", this::myPunishments);
        app.get("/me/active-ban", this::myActiveBan);
        app.get("/me/appeals", this::myAppeals);
        app.post("/me/appeals", this::createAppeal);
        app.get("/appeals", this::listAppeals);
        app.post("/appeals/{id}/approve", this::approveAppeal);
        app.post("/appeals/{id}/deny", this::denyAppeal);
        app.post("/appeals/{id}/shorten", this::shortenAppeal);
        app.get("/players/search", this::searchPlayers);
        app.get("/reasons", this::listReasons);
        app.get("/bans", this::listActiveBans);
        app.get("/bans/{id}", this::getPunishment);
        app.delete("/bans/{id}", this::pardonPunishment);
        app.get("/mutes", this::listActiveMutes);
        app.get("/players/{name}", this::lookupPlayer);
        app.get("/players/{name}/history", this::playerHistory);
        app.get("/reports", this::listReports);
        app.get("/reports/{id}", this::getReport);
        app.post("/reports/{id}/claim", this::claimReport);
        app.post("/reports/{id}/close", this::closeReport);
        app.post("/reports/{id}/teleport", this::teleportToReport);
        app.post("/reports/{id}/ban", this::banFromReport);
        app.post("/reports/{id}/mute", this::muteFromReport);
        app.get("/stats", this::stats);
        app.get("/admin/active-sessions", this::adminActiveSessions);

        // --- Permission engine — admin-only, gated via eternal.web.admin.
        app.get("/admin/permissions/registry", this::permissionRegistry);
        app.get("/admin/roles", this::listRoles);
        app.put("/admin/roles/{name}", this::upsertRole);
        app.delete("/admin/roles/{name}", this::deleteRole);
        app.put("/admin/roles/{name}/permissions/{key}", this::setRolePermission);
        app.delete("/admin/roles/{name}/permissions/{key}", this::clearRolePermission);
        app.get("/admin/users/{uuid}/permissions", this::listUserPermissions);
        app.put("/admin/users/{uuid}/permissions/{key}", this::setUserPermission);
        app.delete("/admin/users/{uuid}/permissions/{key}", this::clearUserPermission);
    }

    /**
     * Lists currently-valid web sessions (token not yet expired). Used by
     * the admin "Active Users" overview to see who is logged into the
     * dashboard right now — split into staff and players via the role.
     * Tokens themselves are NOT included in the response so screen-shots
     * can't accidentally leak credentials.
     */
    private void adminActiveSessions(@NotNull io.javalin.http.Context ctx) {
        auth.requireAdmin(ctx);
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (var s : storage.listActiveSessions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userUuid", s.userUuid().toString());
            m.put("userName", s.userName());
            m.put("role", s.role());
            m.put("createdAt", s.createdAt().toEpochMilli());
            m.put("expiresAt", s.expiresAt().toEpochMilli());
            storage.findProfile(s.userUuid())
                    .ifPresent(p -> m.put("lastDisplayName", p.lastDisplayName()));
            out.add(m);
        }
        ctx.json(out);
    }

    /* --- auth / link flow ---------------------------------------------- */

    private void linkInit(@NotNull Context ctx) {
        String code = generateCode(6);
        String token = generateToken();
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(config.session().linkTtlSeconds());
        storage.createLinkCode(new LinkCode(code, token, LinkCode.Status.PENDING,
                null, null, now, expires));
        ctx.json(Map.of(
                "code", code,
                "linkToken", token,
                "expiresAt", expires.toEpochMilli()
        ));
    }

    private void linkStatus(@NotNull Context ctx) {
        String token = ctx.pathParam("token");
        var link = storage.findLinkByToken(token).orElseThrow(NotFoundResponse::new);
        if (Instant.now().isAfter(link.expiresAt()) && link.status() != LinkCode.Status.CONFIRMED) {
            ctx.json(Map.of("status", "EXPIRED"));
            return;
        }
        if (link.status() == LinkCode.Status.PENDING) {
            ctx.json(Map.of("status", "PENDING"));
            return;
        }
        if (link.status() == LinkCode.Status.CONSUMED) {
            ctx.status(HttpStatus.GONE);
            ctx.json(Map.of("status", "CONSUMED"));
            return;
        }
        // CONFIRMED -> mint a session, mark consumed
        UUID uuid = link.confirmedUuid();
        String name = link.confirmedName();
        if (uuid == null || name == null) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.json(Map.of("error", "link confirmed without identity"));
            return;
        }
        // Role-assignment based on stored tier (see api.yml -> roles).
        int tier = storage.findProfile(uuid).map(p -> p.lastTier()).orElse(0);
        String role;
        if (tier >= config.roles().adminTierThreshold()) role = "ADMIN";
        else if (tier >= config.roles().staffTierThreshold()) role = "MOD";
        else role = "PLAYER";
        String sessionToken = generateToken();
        Instant created = Instant.now();
        Instant expires = created.plusSeconds(config.session().ttlSeconds());
        storage.createSession(new Session(sessionToken, uuid, name, role, created, expires));
        storage.markLinkConsumed(token);

        ctx.json(Map.of(
                "status", "CONFIRMED",
                "sessionToken", sessionToken,
                "user", Map.of("uuid", uuid.toString(), "name", name, "role", role),
                "expiresAt", expires.toEpochMilli()
        ));
    }

    @SuppressWarnings("unchecked")
    private void linkConfirm(@NotNull Context ctx) {
        auth.requireInternal(ctx);
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        if (body == null) throw new BadRequestResponse("body required");
        String code = String.valueOf(body.get("code"));
        String uuidRaw = String.valueOf(body.get("uuid"));
        String name = String.valueOf(body.get("name"));
        UUID uuid;
        try { uuid = UUID.fromString(uuidRaw); }
        catch (IllegalArgumentException ex) { throw new BadRequestResponse("invalid uuid"); }

        boolean ok = storage.confirmLinkCode(code, uuid, name);
        if (!ok) throw new NotFoundResponse("code unknown or expired");
        ctx.json(Map.of("ok", true));
    }

    private void logout(@NotNull Context ctx) {
        Session s = auth.requireSession(ctx);
        storage.deleteSession(s.token());
        ctx.json(Map.of("ok", true));
    }

    /* --- /me / stats / players ----------------------------------------- */

    private void me(@NotNull Context ctx) {
        var p = auth.require(ctx);
        ctx.json(Map.of(
                "name", p.name(),
                "role", p.role(),
                "uuid", p.uuid() == null ? null : p.uuid().toString()
        ));
    }

    private void myPunishments(@NotNull Context ctx) {
        var p = auth.require(ctx);
        if (p.uuid() == null) { ctx.json(List.of()); return; }
        ctx.json(storage.findPunishmentHistory(p.uuid(), null));
    }

    private void myActiveBan(@NotNull Context ctx) {
        var p = auth.require(ctx);
        if (p.uuid() == null) { ctx.status(HttpStatus.NO_CONTENT); return; }
        var ban = storage.findActivePunishment(p.uuid(), PunishmentType.BAN).orElse(null);
        if (ban == null) { ctx.status(HttpStatus.NO_CONTENT); return; }
        ctx.json(ban);
    }

    private void stats(@NotNull Context ctx) {
        var p = auth.requireStaff(ctx);
        var all = storage.staffStats();
        if (p.isAdmin()) { ctx.json(all); return; }
        if (p.uuid() == null) { ctx.json(List.of()); return; }
        ctx.json(all.stream().filter(s -> s.staffUuid().equals(p.uuid())).toList());
    }

    private void lookupPlayer(@NotNull Context ctx) {
        var caller = auth.requireStaff(ctx);
        String name = ctx.pathParam("name");
        var profile = storage.findProfileByName(name)
                .orElseThrow(() -> new NotFoundResponse("player not seen"));

        if (!caller.isAdmin() && tierOf(caller) <= profile.lastTier()) {
            ctx.status(HttpStatus.FORBIDDEN);
            ctx.json(Map.of("error", "blocked by tier"));
            return;
        }

        var activeBan = storage.findActivePunishment(profile.uuid(), PunishmentType.BAN).orElse(null);
        var activeMute = storage.findActivePunishment(profile.uuid(), PunishmentType.MUTE).orElse(null);
        List<PunishmentEntry> history = storage.findPunishmentHistory(profile.uuid(), null);
        List<ReportEntry> reports = storage.findReportsByTarget(profile.uuid());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", profile);
        out.put("activeBan", activeBan);
        out.put("activeMute", activeMute);
        out.put("history", history);
        out.put("reports", reports);
        // Appeals lifecycle visible in the spieler-search so staff can see
        // what the player tried and how it was resolved.
        out.put("appeals", storage.findAppealsByApplicant(profile.uuid()));
        out.put("displayNames", collectDisplayNames(history, reports, activeBan, activeMute));
        ctx.json(out);
    }

    /**
     * Builds a {@code uuid → lastDisplayName} map covering every staff-ish
     * UUID referenced in the given collections. Empty/missing entries are
     * omitted, so the client can {@code obj.displayNames[uuid] ?? name}.
     */
    private @NotNull Map<String, String> collectDisplayNames(
            @NotNull java.util.Collection<PunishmentEntry> punishments,
            @NotNull java.util.Collection<ReportEntry> reports,
            PunishmentEntry... extras) {
        java.util.Set<UUID> uuids = new java.util.HashSet<>();
        for (PunishmentEntry p : punishments) {
            if (p.issuerUuid() != null)       uuids.add(p.issuerUuid());
            if (p.pardonIssuerUuid() != null) uuids.add(p.pardonIssuerUuid());
            if (p.modifiedByUuid() != null)   uuids.add(p.modifiedByUuid());
        }
        for (ReportEntry r : reports) {
            uuids.add(r.reporterUuid());
            if (r.handlerUuid() != null) uuids.add(r.handlerUuid());
        }
        for (PunishmentEntry p : extras) {
            if (p == null) continue;
            if (p.issuerUuid() != null)       uuids.add(p.issuerUuid());
            if (p.pardonIssuerUuid() != null) uuids.add(p.pardonIssuerUuid());
            if (p.modifiedByUuid() != null)   uuids.add(p.modifiedByUuid());
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (UUID u : uuids) {
            storage.findProfile(u).ifPresent(pp -> {
                if (!pp.lastDisplayName().isBlank()) out.put(u.toString(), pp.lastDisplayName());
            });
        }
        return out;
    }

    private void playerHistory(@NotNull Context ctx) {
        var caller = auth.requireStaff(ctx);
        String name = ctx.pathParam("name");
        var profile = storage.findProfileByName(name)
                .orElseThrow(() -> new NotFoundResponse("player not seen"));
        if (!caller.isAdmin() && tierOf(caller) <= profile.lastTier()) {
            ctx.status(HttpStatus.FORBIDDEN);
            ctx.json(Map.of("error", "blocked by tier"));
            return;
        }
        ctx.json(storage.findPunishmentHistory(profile.uuid(), null));
    }

    /* --- bans / mutes -------------------------------------------------- */

    private void listActiveBans(@NotNull Context ctx) {
        auth.requireStaff(ctx);
        var bans = storage.findAllActive(PunishmentType.BAN);
        ctx.json(Map.of(
                "bans", bans,
                "displayNames", collectDisplayNames(bans, java.util.List.of())));
    }

    private void listActiveMutes(@NotNull Context ctx) {
        auth.requireStaff(ctx);
        ctx.json(storage.findAllActive(PunishmentType.MUTE));
    }

    private void getPunishment(@NotNull Context ctx) {
        auth.require(ctx);
        long id = parseLong(ctx, "id");
        ctx.json(storage.findPunishmentById(id).orElseThrow(NotFoundResponse::new));
    }

    /** Public-ish reasons feed for the web UI. Now also carries the
     *  configured appeal-shortening templates so the appeals dialog can
     *  pre-fill the message + duration. */
    private void listReasons(@NotNull Context ctx) {
        auth.requireStaff(ctx);
        java.util.List<Map<String, Object>> reasonRows = new java.util.ArrayList<>();
        for (var r : reasons.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("label", r.label());
            m.put("type", r.type().name());
            m.put("durationSeconds", r.durationSeconds());
            m.put("adminOnly", r.adminOnly());
            m.put("requiredGroupId", r.requiredGroupId());
            reasonRows.add(m);
        }
        java.util.List<Map<String, Object>> templates = new java.util.ArrayList<>();
        for (var t : reasons.appealShortenTemplates()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("label", t.label());
            m.put("durationSeconds",
                    de.eternal.core.time.DurationParser.parseToSeconds(t.duration()));
            m.put("message", t.message());
            templates.add(m);
        }
        ctx.json(Map.of(
                "reasons", reasonRows,
                "appealShortenTemplates", templates));
    }

    private void pardonPunishment(@NotNull Context ctx) {
        // Staff with the regular role may pardon any non-admin-only ban; admin
        // bans (reason marked admin: true in reasons.yml) need a full admin
        // principal. Resolve the reason via the punishment's reason_id —
        // numeric ids match an entry in ReasonsConfig.
        var p = auth.requireStaff(ctx);
        long id = parseLong(ctx, "id");
        var punishment = storage.findPunishmentById(id).orElseThrow(NotFoundResponse::new);
        try {
            int rid = Integer.parseInt(punishment.reasonId());
            var reason = reasons.byId(rid);
            if (reason != null && reason.adminOnly() && !p.isAdmin()) {
                throw new io.javalin.http.ForbiddenResponse("admin-only ban — requires admin role");
            }
        } catch (NumberFormatException ignored) { /* legacy non-numeric reason id */ }

        @SuppressWarnings("unchecked")
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String reason = body == null ? "API pardon"
                : String.valueOf(body.getOrDefault("reason", "API pardon"));
        boolean ok = storage.pardonPunishment(id, p.uuid(), p.name(), reason);
        if (!ok) throw new NotFoundResponse("punishment not active");
        // Replay-Lifecycle: bei jedem Unban die Replays loeschen die
        // gegen diesen Bann verlinkt waren. Permanent-Banns bleiben so
        // ihre Replay-Datei behalten, bis sie aufgehoben werden.
        //
        // An targetUuid + p.uuid() queuen — Konsistenz mit den anderen
        // Pfaden. Der targetUuid (entbannter Spieler) ist meist offline
        // beim Unban, dann landet die Action bei p.uuid() (Admin) wenn
        // er ingame ist. Eine der beiden konsumiert sie irgendwann und
        // löscht den Replay-File von Disk.
        for (var report : storage.findReportsByBanId(id)) {
            String payload = Json.GSON.toJson(Map.of("reportId", report.id()));
            storage.queueAction("DELETE_REPLAY", report.targetUuid(), payload);
            if (p.uuid() != null && !p.uuid().equals(report.targetUuid())) {
                storage.queueAction("DELETE_REPLAY", p.uuid(), payload);
            }
        }
        ctx.json(Map.of("ok", true));
    }

    /* --- reports ------------------------------------------------------- */

    private void listReports(@NotNull Context ctx) {
        auth.requireStaff(ctx);
        String statusParam = ctx.queryParam("status"); // open | claimed | closed | active | all
        int limit = parseIntOr(ctx.queryParam("limit"), 100);
        int offset = parseIntOr(ctx.queryParam("offset"), 0);

        java.util.List<de.eternal.core.model.ReportStatus> statuses = new java.util.ArrayList<>();
        if (statusParam == null || statusParam.equalsIgnoreCase("active")) {
            statuses.add(de.eternal.core.model.ReportStatus.OPEN);
            statuses.add(de.eternal.core.model.ReportStatus.CLAIMED);
        } else if (statusParam.equalsIgnoreCase("all")) {
            // empty list = all
        } else {
            try {
                statuses.add(de.eternal.core.model.ReportStatus.valueOf(statusParam.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                throw new BadRequestResponse("Unknown status: " + statusParam);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", storage.countReports(statuses));
        out.put("items", storage.findReports(statuses, limit, offset));
        ctx.json(out);
    }

    private int parseIntOr(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s); } catch (NumberFormatException ex) { return fallback; }
    }

    private void getReport(@NotNull Context ctx) {
        auth.requireStaff(ctx);
        long id = parseLong(ctx, "id");
        ctx.json(storage.findReport(id).orElseThrow(NotFoundResponse::new));
    }

    private void claimReport(@NotNull Context ctx) {
        var p = auth.requireStaff(ctx);
        if (p.uuid() == null) {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of("error", "api key has no associated uuid — cannot claim"));
            return;
        }
        long id = parseLong(ctx, "id");
        boolean ok = storage.claimReport(id, p.uuid(), p.name());
        if (!ok) throw new NotFoundResponse("report not open");
        ctx.json(Map.of("ok", true));
    }

    @SuppressWarnings("unchecked")
    private void closeReport(@NotNull Context ctx) {
        var caller = auth.requireStaff(ctx);
        long id = parseLong(ctx, "id");
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String resolution = body == null ? "closed via API"
                : String.valueOf(body.getOrDefault("resolution", "closed via API"));
        // Report-Objekt vorher holen — wir brauchen targetUuid für das
        // Action-Routing (siehe Kommentar unten).
        ReportEntry report = storage.findReport(id).orElseThrow(NotFoundResponse::new);
        // Vor dem Schliessen schauen ob ein Bann mit diesem Report verlinkt
        // ist — wenn nein → Replay loeschen, wenn ja → Replay behalten
        // (faellt erst beim Unban). banFromReport hat vorher
        // linkReportToBan aufgerufen, wenn aus dem Report ein Bann wurde.
        boolean hasBan = storage.findBanForReport(id).isPresent();
        boolean ok = storage.closeReport(id, resolution);
        if (!ok) throw new NotFoundResponse();
        // END_CAPTURE/DELETE_REPLAY werden an targetUuid + caller.uuid()
        // gequeued — siehe Kommentar in banFromReport. In Multi-Server-
        // Setups ist sonst entweder der Recorder oder die Playback-Session
        // nicht erreichbar.
        String payload = Json.GSON.toJson(Map.of("reportId", id));
        storage.queueAction("END_CAPTURE", report.targetUuid(), payload);
        if (caller.uuid() != null && !caller.uuid().equals(report.targetUuid())) {
            storage.queueAction("END_CAPTURE", caller.uuid(), payload);
        }
        if (!hasBan) {
            // Kein Bann → Replay ist Beweismaterial-frei und kann weg.
            storage.queueAction("DELETE_REPLAY", report.targetUuid(), payload);
            if (caller.uuid() != null && !caller.uuid().equals(report.targetUuid())) {
                storage.queueAction("DELETE_REPLAY", caller.uuid(), payload);
            }
        }
        ctx.json(Map.of("ok", true));
    }

    /** Web -> Ingame: queue a teleport for the calling mod to the report's target. */
    private void teleportToReport(@NotNull Context ctx) {
        var p = auth.requireStaff(ctx);
        if (p.uuid() == null) {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of("error", "api key has no associated uuid — cannot teleport"));
            return;
        }
        long id = parseLong(ctx, "id");
        var report = storage.findReport(id).orElseThrow(NotFoundResponse::new);
        String payload = Json.GSON.toJson(Map.of(
                "kind", "teleport-to-report",
                "reportId", report.id(),
                "targetUuid", report.targetUuid().toString(),
                "targetName", report.targetName()
        ));
        long actionId = storage.queueAction("TELEPORT", p.uuid(), payload);
        ctx.json(Map.of("ok", true, "actionId", actionId));
    }

    /* --- appeals ------------------------------------------------------- */

    @SuppressWarnings("unchecked")
    private void publicAppeal(@NotNull Context ctx) {
        // KEINE Authentifizierung — gebannte Spieler koennen ja nicht ingame
        // verlinken. Identitaet ueber Spielername + Bann-ID + active-check.
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        if (body == null) throw new BadRequestResponse("body required");

        String playerName = String.valueOf(body.getOrDefault("playerName", "")).trim();
        Object banIdRaw = body.get("banId");
        long banId;
        try {
            banId = banIdRaw instanceof Number n ? n.longValue()
                    : Long.parseLong(String.valueOf(banIdRaw));
        } catch (NumberFormatException ex) { throw new BadRequestResponse("banId must be numeric"); }
        String text = String.valueOf(body.getOrDefault("text", "")).trim();

        if (playerName.isEmpty()) throw new BadRequestResponse("playerName required");
        if (banId <= 0) throw new BadRequestResponse("banId required");
        if (text.length() < 10) throw new BadRequestResponse("text too short (>= 10 chars)");

        var ban = storage.findPunishmentById(banId)
                .orElseThrow(() -> new NotFoundResponse("ban not found"));
        if (ban.type() != PunishmentType.BAN) throw new BadRequestResponse("entry is not a ban");
        if (!ban.targetName().equalsIgnoreCase(playerName)) {
            throw new BadRequestResponse("playerName does not match the ban");
        }
        if (!ban.active()) throw new BadRequestResponse("ban is no longer active");

        for (UnbanAppeal a : storage.findAppealsByApplicant(ban.targetUuid())) {
            if (a.banId() == banId && a.status() == UnbanAppeal.Status.PENDING) {
                ctx.status(HttpStatus.CONFLICT);
                ctx.json(Map.of("error", "Pending appeal already exists", "appealId", a.id()));
                return;
            }
        }

        long id = storage.createAppeal(new UnbanAppeal(
                -1L, banId, ban.targetUuid(), ban.targetName(), text,
                UnbanAppeal.Status.PENDING, Instant.now(),
                null, null, null, null, null, null
        ));
        ctx.json(Map.of("ok", true, "appealId", id));
    }

    private void myAppeals(@NotNull Context ctx) {
        var p = auth.require(ctx);
        if (p.uuid() == null) { ctx.json(List.of()); return; }
        ctx.json(storage.findAppealsByApplicant(p.uuid()));
    }

    @SuppressWarnings("unchecked")
    private void createAppeal(@NotNull Context ctx) {
        var p = auth.require(ctx);
        if (p.uuid() == null) throw new BadRequestResponse("api-key has no uuid — cannot file appeal");

        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        if (body == null) throw new BadRequestResponse("body required");
        String text = String.valueOf(body.getOrDefault("text", "")).trim();
        if (text.isEmpty()) throw new BadRequestResponse("text required");

        var activeBan = storage.findActivePunishment(p.uuid(), PunishmentType.BAN)
                .orElseThrow(() -> new BadRequestResponse("no active ban to appeal"));

        // Pending appeal already exists?
        for (UnbanAppeal a : storage.findAppealsByApplicant(p.uuid())) {
            if (a.banId() == activeBan.id() && a.status() == UnbanAppeal.Status.PENDING) {
                ctx.status(HttpStatus.CONFLICT);
                ctx.json(Map.of("error", "Pending appeal already exists", "appealId", a.id()));
                return;
            }
        }

        long id = storage.createAppeal(new UnbanAppeal(
                -1L, activeBan.id(), p.uuid(), p.name(), text,
                UnbanAppeal.Status.PENDING, Instant.now(),
                null, null, null, null, null, null
        ));
        ctx.json(Map.of("ok", true, "appealId", id));
    }

    private void listAppeals(@NotNull Context ctx) {
        auth.requireStaff(ctx);
        String statusParam = ctx.queryParam("status");
        UnbanAppeal.Status status = statusParam == null
                ? UnbanAppeal.Status.PENDING
                : UnbanAppeal.Status.valueOf(statusParam.toUpperCase());
        ctx.json(storage.findAppealsByStatus(status));
    }

    @SuppressWarnings("unchecked")
    private void approveAppeal(@NotNull Context ctx) {
        var p = auth.requireAdmin(ctx);
        long id = parseLong(ctx, "id");
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String reason = body == null ? "Antrag bestaetigt"
                : String.valueOf(body.getOrDefault("reason", "Antrag bestaetigt"));

        UnbanAppeal appeal = storage.findAppeal(id).orElseThrow(NotFoundResponse::new);
        boolean ok = storage.decideAppeal(id, p.uuid(), p.name(), UnbanAppeal.Status.APPROVED, reason);
        if (!ok) throw new BadRequestResponse("Appeal not pending");

        // Pardon the ban
        storage.pardonPunishment(appeal.banId(), p.uuid(), p.name(),
                "Entbannungsantrag #" + id + " bestaetigt: " + reason);
        ctx.json(Map.of("ok", true));
    }

    @SuppressWarnings("unchecked")
    private void denyAppeal(@NotNull Context ctx) {
        var p = auth.requireAdmin(ctx);
        long id = parseLong(ctx, "id");
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String reason = body == null ? "Antrag abgelehnt"
                : String.valueOf(body.getOrDefault("reason", "Antrag abgelehnt"));
        UnbanAppeal appeal = storage.findAppeal(id).orElseThrow(NotFoundResponse::new);
        boolean ok = storage.decideAppeal(id, p.uuid(), p.name(), UnbanAppeal.Status.DENIED, reason);
        if (!ok) throw new BadRequestResponse("Appeal not pending");
        // Surface the rejection on the player's next kick-screen so they
        // know not to keep retrying. Uses setLastAppealMessage (NOT
        // modifyPunishmentDuration) — duration must stay untouched on a
        // deny; only the note changes.
        if (storage instanceof de.eternal.core.storage.sql.SqlStorage sql) {
            sql.setLastAppealMessage(appeal.banId(), "Antrag abgelehnt: " + reason);
        }
        ctx.json(Map.of("ok", true));
    }

    /**
     * Third appeal decision path: shorten the ban instead of full pardon /
     * rejection. Mods can do this WITHOUT {@code eternal.modify.duration} —
     * the shortening is appeal-scoped and the audit trail goes through the
     * appeal record + the punishment's last_appeal_message column.
     */
    @SuppressWarnings("unchecked")
    private void shortenAppeal(@NotNull io.javalin.http.Context ctx) {
        var p = auth.requireStaff(ctx);
        long id = parseLong(ctx, "id");
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        if (body == null) throw new BadRequestResponse("missing body");
        // Bevorzugt: "duration" als DurationParser-String (1d, 6h, 30m,
        // permanent). Fallback fuer Alt-Clients: "remainingSeconds" als
        // Zahl. Frontend schickt seit jetzt nur noch duration.
        long remainingSec;
        Object durObj = body.get("duration");
        if (durObj instanceof String s && !s.isBlank()) {
            remainingSec = de.eternal.core.time.DurationParser.parseToSeconds(s);
            if (remainingSec < 0) remainingSec = -1; // permanent
        } else if (body.get("remainingSeconds") instanceof Number n) {
            remainingSec = n.longValue();
        } else {
            throw new BadRequestResponse("either 'duration' (string) or 'remainingSeconds' required");
        }

        UnbanAppeal appeal = storage.findAppeal(id).orElseThrow(NotFoundResponse::new);
        if (appeal.status() != UnbanAppeal.Status.PENDING) {
            throw new BadRequestResponse("Appeal not pending");
        }

        // Decide appeal first so the audit trail is committed even if the
        // duration change races against the user joining. Permanent
        // shortening (rare, but possible) wird via expires_at=NULL umgesetzt.
        java.time.Instant newExpires = remainingSec < 0 ? null
                : java.time.Instant.now().plusSeconds(remainingSec);
        String auditReason = remainingSec < 0
                ? "Verkuerzt auf permanent"
                : "Verkuerzt auf " + remainingSec + "s";
        // Auto-generated player-facing note for the next kick screen.
        // The frontend deliberately doesn't ask the mod for a message
        // (too much friction) — but the player should still see WHY
        // their kick line changed, so we synthesize one.
        String playerNote = remainingSec < 0
                ? "" /* permanent: nothing to inform about */
                : remainingSec == 0
                    ? "Antrag akzeptiert — du wurdest entbannt."
                    : "Antrag akzeptiert — Bann verkuerzt auf "
                        + de.eternal.core.time.DurationParser.formatRemaining(remainingSec) + ".";
        if (storage instanceof de.eternal.core.storage.sql.SqlStorage sql) {
            sql.decideAppealFull(id, p.uuid(), p.name(),
                    UnbanAppeal.Status.SHORTENED, auditReason, null,
                    remainingSec < 0 ? null : remainingSec);
            sql.modifyPunishmentDuration(appeal.banId(), p.uuid(), p.name(), newExpires,
                    playerNote.isEmpty() ? null : playerNote);
        } else {
            storage.decideAppeal(id, p.uuid(), p.name(),
                    UnbanAppeal.Status.SHORTENED, auditReason);
            storage.modifyPunishmentDuration(appeal.banId(), p.uuid(), p.name(), newExpires);
        }
        ctx.json(Map.of("ok", true,
                "newExpiresAt", newExpires == null ? -1L : newExpires.toEpochMilli()));
    }

    /**
     * Autocomplete for the dashboard player search. Matches name-prefix
     * AND uuid-prefix (case-insensitive). Up to 10 results.
     */
    private void searchPlayers(@NotNull io.javalin.http.Context ctx) {
        auth.requireStaff(ctx);
        String q = ctx.queryParam("q");
        if (q == null || q.trim().length() < 2) {
            ctx.json(java.util.List.of());
            return;
        }
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (storage instanceof de.eternal.core.storage.sql.SqlStorage sql) {
            for (var pp : sql.searchProfiles(q.trim(), 10)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("uuid", pp.uuid().toString());
                m.put("name", pp.name());
                m.put("lastDisplayName", pp.lastDisplayName());
                m.put("lastGroupName", pp.lastGroupName());
                m.put("lastSeen", pp.lastSeen().toEpochMilli());
                out.add(m);
            }
        }
        ctx.json(out);
    }

    /* --- ban from report ----------------------------------------------- */

    @SuppressWarnings("unchecked")
    private void banFromReport(@NotNull Context ctx) {
        var p = auth.requireStaff(ctx);
        if (p.uuid() == null) throw new BadRequestResponse("api-key has no uuid");
        long reportId = parseLong(ctx, "id");
        ReportEntry report = storage.findReport(reportId).orElseThrow(NotFoundResponse::new);

        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        if (body == null) throw new BadRequestResponse("body required");
        String label = String.valueOf(body.getOrDefault("reasonLabel", "Web-Bann (Report #" + reportId + ")"));
        long durationSec = body.get("durationSeconds") instanceof Number n ? n.longValue() : -1L;
        String message = String.valueOf(body.getOrDefault("message", label));
        String reasonIdStr = String.valueOf(body.getOrDefault("reasonId", "web"));

        Instant now = Instant.now();
        Instant expires = durationSec < 0 ? null : now.plusSeconds(durationSec);
        PunishmentEntry draft = new PunishmentEntry(
                -1L, PunishmentType.BAN,
                report.targetUuid(), report.targetName(),
                p.uuid(), p.name(),
                reasonIdStr, label, message,
                now, expires, true,
                null, null, null, null,
                null, null, null,
                null, null
        );
        long banId = storage.insertPunishment(draft);
        storage.closeReport(reportId, "Banned (#" + banId + "): " + label + " durch " + p.name());
        storage.linkReportToBan(reportId, banId);
        // Replay-Lifecycle: bei einem ban-from-report wird der Bann ausgeloest,
        // daher KEIN DELETE_REPLAY (das passiert erst beim Unban) — wohl aber
        // END_CAPTURE damit das in-flight Recording sauber auf Disk landet
        // und im /history-Eintrag verlinkt bleibt.
        //
        // END_CAPTURE wird an ZWEI UUIDs gequeued:
        //   1. report.targetUuid() — der Reportee. Sein Spigot beendet das
        //      in-flight Recording (endCaptureForReport).
        //   2. p.uuid() — der Mod selber. Sein Spigot stoppt jede aktive
        //      Playback-Session (stopAllPlaybackOfReport), so dass er aus
        //      dem Spectator-Replay rauskommt. In Multi-Server-Setups
        //      kann der Mod auf einem anderen Spigot online sein als der
        //      Reportee — beide Actions queuen sorgt dafür, dass beide
        //      Server-Seiten unabhängig die richtigen Hooks feuern.
        // Falls Mod und Reportee auf demselben Server sind, ist die zweite
        // Action ein No-Op (stopPlayback hat schon nichts mehr zu stoppen
        // weil die erste Action es geschafft hat).
        String endCapturePayload = Json.GSON.toJson(Map.of("reportId", reportId));
        storage.queueAction("END_CAPTURE", report.targetUuid(), endCapturePayload);
        if (p.uuid() != null && !p.uuid().equals(report.targetUuid())) {
            storage.queueAction("END_CAPTURE", p.uuid(), endCapturePayload);
        }
        // Queue a KICK against the BANNED player's UUID — ihr Spigot's
        // ActionPoller findet ihn online und kickt ihn sofort. Ohne das
        // bleibt der Spieler eingeloggt bis er von alleine rejoint.
        String durationLabel = durationSec < 0 ? "permanent" : (durationSec + "s");
        String kickScreen = "&dEternal &8»\n\n&7Du wurdest vom Netzwerk &cgebannt&7.\n\n"
                + "&dGrund&8: &b" + label + "\n"
                + "&dDauer&8: &7" + durationLabel + "\n"
                + "&dBann-ID&8: &c#" + banId + "\n\n"
                + "&dBeschwerde&8: &f/appeal";
        storage.queueAction("KICK", report.targetUuid(),
                Json.GSON.toJson(Map.of(
                        "reason", "Banned: " + label,
                        "screen", kickScreen)));
        // In-game staff broadcast — same format as the /ban command's
        // ban-broadcast translation. Goes via target's spigot →
        // plugin-message → Bungee → all online staff cross-server.
        // Target is guaranteed online (sonst kein in-flight Recording),
        // so the carrier UUID is safe to use here.
        String banBroadcast = "&dEternal &8» &e" + report.targetName()
                + " &7wurde von &e" + p.name() + "&7 gebannt&8: &b" + label
                + " &7(&7" + durationLabel + "&7)";
        storage.queueAction("BROADCAST", report.targetUuid(),
                Json.GSON.toJson(Map.of("message", banBroadcast)));
        ctx.json(Map.of("ok", true, "banId", banId, "reportId", reportId));
    }

    /* --- mute from report ---------------------------------------------- */

    /**
     * Web counterpart to {@code banFromReport} — issues a MUTE instead of
     * a BAN. The reportee stays online (no KICK queued — they can keep
     * playing), but their chat is gagged for {@code durationSeconds}.
     * Report gets closed + linked to the mute the same way bans link.
     * Replay is preserved (mutes don't auto-delete recordings).
     */
    @SuppressWarnings("unchecked")
    private void muteFromReport(@NotNull Context ctx) {
        var p = auth.requireStaff(ctx);
        if (p.uuid() == null) throw new BadRequestResponse("api-key has no uuid");
        long reportId = parseLong(ctx, "id");
        ReportEntry report = storage.findReport(reportId).orElseThrow(NotFoundResponse::new);

        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        if (body == null) throw new BadRequestResponse("body required");
        String label = String.valueOf(body.getOrDefault("reasonLabel", "Web-Mute (Report #" + reportId + ")"));
        long durationSec = body.get("durationSeconds") instanceof Number n ? n.longValue() : -1L;
        String message = String.valueOf(body.getOrDefault("message", label));
        String reasonIdStr = String.valueOf(body.getOrDefault("reasonId", "web"));

        Instant now = Instant.now();
        Instant expires = durationSec < 0 ? null : now.plusSeconds(durationSec);
        PunishmentEntry draft = new PunishmentEntry(
                -1L, PunishmentType.MUTE,
                report.targetUuid(), report.targetName(),
                p.uuid(), p.name(),
                reasonIdStr, label, message,
                now, expires, true,
                null, null, null, null,
                null, null, null,
                null, null
        );
        long muteId = storage.insertPunishment(draft);
        storage.closeReport(reportId, "Muted (#" + muteId + "): " + label + " durch " + p.name());
        storage.linkReportToBan(reportId, muteId);
        // Same replay-lifecycle as ban: END_CAPTURE flushes the in-flight
        // recording so the mute has a replay attached for audit; the file
        // stays alive until the mute expires + gets unmuted (or admin
        // manually deletes it). DELETE_REPLAY is NOT queued here.
        // Queue an targetUuid + p.uuid() für Multi-Server-Setups —
        // siehe banFromReport-Kommentar.
        String mutePayload = Json.GSON.toJson(Map.of("reportId", reportId));
        storage.queueAction("END_CAPTURE", report.targetUuid(), mutePayload);
        if (!p.uuid().equals(report.targetUuid())) {
            storage.queueAction("END_CAPTURE", p.uuid(), mutePayload);
        }
        // Staff broadcast — same shape as the in-game /mute mute-broadcast
        // translation. Goes target → spigot → Bungee → all online staff.
        String muteDurationLabel = durationSec < 0 ? "permanent" : (durationSec + "s");
        String muteBroadcast = "&dEternal &8» &e" + report.targetName()
                + " &7wurde von &e" + p.name() + "&7 gemutet&8: &b" + label
                + " &7(&7" + muteDurationLabel + "&7)";
        storage.queueAction("BROADCAST", report.targetUuid(),
                Json.GSON.toJson(Map.of("message", muteBroadcast)));
        ctx.json(Map.of("ok", true, "muteId", muteId, "reportId", reportId));
    }

    /* --- plugin polling ------------------------------------------------ */

    private void pendingActions(@NotNull Context ctx) {
        auth.requireInternal(ctx);
        String uuidRaw = ctx.queryParam("uuid");
        if (uuidRaw == null) throw new BadRequestResponse("uuid query param required");
        UUID uuid;
        try { uuid = UUID.fromString(uuidRaw); }
        catch (IllegalArgumentException ex) { throw new BadRequestResponse("invalid uuid"); }
        ctx.json(storage.pendingActionsFor(uuid));
    }

    private void consumeAction(@NotNull Context ctx) {
        auth.requireInternal(ctx);
        long id = parseLong(ctx, "id");
        if (!storage.consumeAction(id)) throw new NotFoundResponse();
        ctx.json(Map.of("ok", true));
    }

    /* --- helpers ------------------------------------------------------- */

    private int tierOf(@NotNull Auth.Principal p) {
        if (p.isAdmin()) return Integer.MAX_VALUE;
        if (p.uuid() == null) return 0;
        return storage.findProfile(p.uuid()).map(pp -> pp.lastTier()).orElse(0);
    }

    private long parseLong(@NotNull Context ctx, @NotNull String name) {
        try { return Long.parseLong(ctx.pathParam(name)); }
        catch (NumberFormatException ex) { throw new BadRequestResponse(name + " must be a number"); }
    }

    private @NotNull String generateCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(CODE_ALPHABET[RNG.nextInt(CODE_ALPHABET.length)]);
        return sb.toString();
    }

    private @NotNull String generateToken() {
        byte[] buf = new byte[32];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /* =====================================================================
     * Permission-engine endpoints
     * All gated through eternal.web.admin so admins can keep mods out of
     * the role-and-permission editor itself. The legacy requireAdmin
     * stays in place underneath as a belt-and-braces check until the
     * frontend can rely on the registry-driven gate.
     * ===================================================================== */

    private void permissionRegistry(@NotNull Context ctx) {
        auth.requirePermission(ctx, "eternal.web.admin");
        var registry = auth.permissions().registry();
        // Group by category so the admin UI can render section headers
        // without re-bucketing on the client.
        Map<String, java.util.List<Map<String, Object>>> grouped = new java.util.LinkedHashMap<>();
        for (var entry : registry.byCategory().entrySet()) {
            java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (var e : entry.getValue()) {
                rows.add(Map.of(
                        "key", e.key(),
                        "label", e.label(),
                        "description", e.description(),
                        "defaultGrant", e.defaultGrant().name()));
            }
            grouped.put(entry.getKey(), rows);
        }
        ctx.json(Map.of("categories", grouped));
    }

    private void listRoles(@NotNull Context ctx) {
        auth.requirePermission(ctx, "eternal.web.admin");
        var permStorage = permStorage();
        var roles = permStorage.listRoles();
        // For each role, embed its grants so the admin UI gets the
        // whole picture in one call (avoid N+1 from the dashboard).
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>(roles.size());
        for (var r : roles) {
            var grants = permStorage.rolePermissions(r.name());
            java.util.List<Map<String, Object>> grantsList = new java.util.ArrayList<>(grants.size());
            for (var g : grants.values()) {
                grantsList.add(Map.of(
                        "key", g.permissionKey(),
                        "granted", g.granted(),
                        "updatedAt", g.updatedAt().toEpochMilli(),
                        "updatedBy", g.updatedBy() == null ? "" : g.updatedBy()));
            }
            out.add(Map.of(
                    "name", r.name(),
                    "displayName", r.displayName(),
                    "mcGroupName", r.mcGroupName(),
                    "sortOrder", r.sortOrder(),
                    "color", r.color(),
                    "permissions", grantsList));
        }
        ctx.json(Map.of("roles", out));
    }

    @SuppressWarnings("unchecked")
    private void upsertRole(@NotNull Context ctx) {
        auth.requirePermission(ctx, "eternal.web.admin");
        String name = ctx.pathParam("name");
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        if (body == null) throw new BadRequestResponse("body required");
        String display = String.valueOf(body.getOrDefault("displayName", name));
        String mcGroup = String.valueOf(body.getOrDefault("mcGroupName", name.toLowerCase()));
        int sortOrder = body.get("sortOrder") instanceof Number n ? n.intValue() : 0;
        String color = String.valueOf(body.getOrDefault("color", "&7"));
        // Preserve original created_at on update — pull the existing row
        // first; default to now() for inserts.
        var existing = permStorage().findRole(name);
        Instant createdAt = existing.map(de.eternal.core.model.Role::createdAt).orElse(Instant.now());
        var role = new de.eternal.core.model.Role(name, display, mcGroup, sortOrder, color, createdAt);
        permStorage().upsertRole(role);
        ctx.json(Map.of("ok", true, "created", existing.isEmpty()));
    }

    private void deleteRole(@NotNull Context ctx) {
        auth.requirePermission(ctx, "eternal.web.admin");
        String name = ctx.pathParam("name");
        boolean removed = permStorage().deleteRole(name);
        if (!removed) throw new NotFoundResponse();
        ctx.json(Map.of("ok", true));
    }

    @SuppressWarnings("unchecked")
    private void setRolePermission(@NotNull Context ctx) {
        var caller = auth.requirePermission(ctx, "eternal.web.admin");
        String role = ctx.pathParam("name");
        String key = ctx.pathParam("key");
        if (!auth.permissions().registry().knows(key)) {
            throw new BadRequestResponse("unknown permission key: " + key);
        }
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        boolean granted = body != null && Boolean.TRUE.equals(body.get("granted"));
        permStorage().setRolePermission(role, key, granted, caller.name());
        ctx.json(Map.of("ok", true));
    }

    private void clearRolePermission(@NotNull Context ctx) {
        auth.requirePermission(ctx, "eternal.web.admin");
        String role = ctx.pathParam("name");
        String key = ctx.pathParam("key");
        boolean removed = permStorage().clearRolePermission(role, key);
        ctx.json(Map.of("ok", true, "cleared", removed));
    }

    private void listUserPermissions(@NotNull Context ctx) {
        auth.requirePermission(ctx, "eternal.web.admin");
        UUID uuid;
        try { uuid = UUID.fromString(ctx.pathParam("uuid")); }
        catch (IllegalArgumentException ex) { throw new BadRequestResponse("invalid uuid"); }

        var perms = permStorage().userPermissions(uuid);
        java.util.List<Map<String, Object>> overrides = new java.util.ArrayList<>(perms.size());
        for (var g : perms.values()) {
            overrides.add(Map.of(
                    "key", g.permissionKey(),
                    "granted", g.granted(),
                    "updatedAt", g.updatedAt().toEpochMilli(),
                    "updatedBy", g.updatedBy() == null ? "" : g.updatedBy()));
        }
        // Also surface the resolved role for context, so the UI can show
        // "this user is in role 'mod' and gets these grants by default".
        var profile = storage.findProfile(uuid).orElse(null);
        String resolvedRole = "";
        if (profile != null && !profile.lastGroupName().isEmpty()) {
            resolvedRole = permStorage().findRoleByMcGroup(profile.lastGroupName())
                    .map(de.eternal.core.model.Role::name).orElse("");
        }
        ctx.json(Map.of("overrides", overrides, "resolvedRole", resolvedRole));
    }

    @SuppressWarnings("unchecked")
    private void setUserPermission(@NotNull Context ctx) {
        var caller = auth.requirePermission(ctx, "eternal.web.admin");
        UUID uuid;
        try { uuid = UUID.fromString(ctx.pathParam("uuid")); }
        catch (IllegalArgumentException ex) { throw new BadRequestResponse("invalid uuid"); }
        String key = ctx.pathParam("key");
        if (!auth.permissions().registry().knows(key)) {
            throw new BadRequestResponse("unknown permission key: " + key);
        }
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        boolean granted = body != null && Boolean.TRUE.equals(body.get("granted"));
        permStorage().setUserPermission(uuid, key, granted, caller.name());
        ctx.json(Map.of("ok", true));
    }

    private void clearUserPermission(@NotNull Context ctx) {
        auth.requirePermission(ctx, "eternal.web.admin");
        UUID uuid;
        try { uuid = UUID.fromString(ctx.pathParam("uuid")); }
        catch (IllegalArgumentException ex) { throw new BadRequestResponse("invalid uuid"); }
        String key = ctx.pathParam("key");
        boolean removed = permStorage().clearUserPermission(uuid, key);
        ctx.json(Map.of("ok", true, "cleared", removed));
    }

    /** SqlStorage implements both interfaces, so we cast for the call
     *  sites that need the permission-side methods. Pre-validated by
     *  the Main bootstrap, so the cast is safe at runtime. */
    private de.eternal.core.permission.@NotNull PermissionStorage permStorage() {
        return (de.eternal.core.permission.PermissionStorage) storage;
    }
}
