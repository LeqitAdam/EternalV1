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
        app.get("/stats", this::stats);
        app.get("/admin/active-sessions", this::adminActiveSessions);
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
        ctx.json(out);
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
        ctx.json(storage.findAllActive(PunishmentType.BAN));
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

    /** Public-ish reasons feed for the web UI — frontend joins this to bans
     * via {@code reason_id} so it can grey out the pardon button on admin bans. */
    private void listReasons(@NotNull Context ctx) {
        auth.requireStaff(ctx);
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (var r : reasons.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("label", r.label());
            m.put("type", r.type().name());
            m.put("durationSeconds", r.durationSeconds());
            m.put("adminOnly", r.adminOnly());
            m.put("requiredGroupId", r.requiredGroupId());
            out.add(m);
        }
        ctx.json(out);
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
        auth.requireStaff(ctx);
        long id = parseLong(ctx, "id");
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String resolution = body == null ? "closed via API"
                : String.valueOf(body.getOrDefault("resolution", "closed via API"));
        boolean ok = storage.closeReport(id, resolution);
        if (!ok) throw new NotFoundResponse();
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
                null, null, null, null
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
                null, null, null, null
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
        boolean ok = storage.decideAppeal(id, p.uuid(), p.name(), UnbanAppeal.Status.DENIED, reason);
        if (!ok) throw new BadRequestResponse("Appeal not pending");
        ctx.json(Map.of("ok", true));
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
                null, null, null
        );
        long banId = storage.insertPunishment(draft);
        storage.closeReport(reportId, "Banned (#" + banId + "): " + label + " durch " + p.name());
        storage.linkReportToBan(reportId, banId);
        ctx.json(Map.of("ok", true, "banId", banId, "reportId", reportId));
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
}
