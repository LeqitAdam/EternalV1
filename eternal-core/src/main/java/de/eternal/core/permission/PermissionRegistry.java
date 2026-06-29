package de.eternal.core.permission;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central catalogue of every permission key the system knows about,
 * grouped into sections for the admin UI. Each entry carries its
 * description, the category it belongs to, and its hardcoded
 * "factory default" — what would happen if neither a role row nor a
 * user row exists for that key.
 *
 * <p>Permission keys live in two flavours:</p>
 * <ul>
 *     <li><b>Static</b> — declared here at startup (e.g.
 *         {@code eternal.ban}, {@code eternal.web.dashboard}).</li>
 *     <li><b>Dynamic</b> — generated at runtime by callers (e.g.
 *         {@code eternal.ban.reason.42} once a reason with id 42
 *         exists in {@code reasons.yml}). Dynamic entries are added
 *         via {@link #registerReasonScoped}.</li>
 * </ul>
 *
 * <p>The registry is mutable but only ever appended to — entries are
 * never removed at runtime so cached references stay valid.</p>
 */
public final class PermissionRegistry {

    /** Default tier-floor that a permission applies to. Used by the
     *  resolver as the "hardcoded fallback" when no role / user row
     *  exists. STAFF_ANY = any staff (admin + mod), ADMIN_ONLY = only
     *  admins, EVERYONE = everyone authenticated, NEVER = no default
     *  grant (must be explicitly granted). */
    public enum DefaultGrant { NEVER, ADMIN_ONLY, STAFF_ANY, EVERYONE }

    public record Entry(
            @NotNull String key,
            @NotNull String category,
            @NotNull String label,
            @NotNull String description,
            @NotNull DefaultGrant defaultGrant,
            /** Keys this permission is effectively useless without — the UI
             *  surfaces them as "wirkt nur mit". The behaviour is unchanged;
             *  this is purely an editor/order-page hint so admins and
             *  requesters see what else hangs off a key. */
            @NotNull List<String> requires,
            /** Companion keys that are recommended together but not strictly
             *  required ("sinnvoll dazu"). */
            @NotNull List<String> relatedTo
    ) {
        public Entry {
            requires = requires == null ? List.of() : List.copyOf(requires);
            relatedTo = relatedTo == null ? List.of() : List.copyOf(relatedTo);
        }
    }

    private final Map<String, Entry> byKey = new LinkedHashMap<>();

    public PermissionRegistry() {
        registerCoreKeys();
    }

    /** The hand-curated set of permission keys the codebase actually
     *  checks. Keep in sync with the Auth + Routes call sites. */
    private void registerCoreKeys() {
        // --- Punishment actions ---
        register("eternal.ban",   "punishments", "Spieler bannen",
                "Darf reguläre Banns aussprechen.", DefaultGrant.STAFF_ANY,
                List.of(), List.of("eternal.report.handle"));
        register("eternal.ban.admin", "punishments", "Admin-Banns",
                "Darf Reasons mit admin=true verwenden und löschen.", DefaultGrant.ADMIN_ONLY,
                List.of("eternal.ban"), List.of());
        register("eternal.mute",  "punishments", "Spieler muten",
                "Darf reguläre Mutes aussprechen.", DefaultGrant.STAFF_ANY,
                List.of(), List.of("eternal.report.handle"));
        register("eternal.unban", "punishments", "Spieler entbannen",
                "Darf reguläre Banns aufheben.", DefaultGrant.STAFF_ANY);
        register("eternal.unban.admin", "punishments", "Admin-Banns aufheben",
                "Darf Admin-Banns aufheben.", DefaultGrant.ADMIN_ONLY,
                List.of("eternal.unban"), List.of());
        register("eternal.modify.duration", "punishments", "Dauer ändern",
                "Darf /modify setduration aufrufen.", DefaultGrant.ADMIN_ONLY);
        register("eternal.modify.reason", "punishments", "Grund ändern",
                "Darf /modify setreason aufrufen.", DefaultGrant.ADMIN_ONLY);
        register("eternal.history.reset", "punishments", "History reset",
                "Darf /resethistory aufrufen.", DefaultGrant.ADMIN_ONLY);
        register("eternal.bypass", "punishments", "Tier-Schutz umgehen",
                "Ignoriert den Tier-Schutz beim Lookup (sonst muss der eigene "
                        + "Rang höher als der des Ziels sein).", DefaultGrant.ADMIN_ONLY);

        // --- Reports ---
        register("eternal.report",  "reports", "Spieler reporten",
                "Darf /report einen anderen Spieler.", DefaultGrant.EVERYONE);
        register("eternal.report.handle", "reports", "Reports bearbeiten",
                "Darf Reports im Dashboard claimen / schließen / bannen.",
                DefaultGrant.STAFF_ANY, List.of(), List.of("eternal.web.player.view"));
        register("eternal.report.notify", "reports", "Report-Notifications",
                "Empfängt Broadcasts bei neuen Reports.", DefaultGrant.STAFF_ANY);

        // --- Replay ---
        register("eternal.replay.rewatch", "replay", "Replays ansehen",
                "/replay list / play / reports.", DefaultGrant.STAFF_ANY);
        register("eternal.replay.debug", "replay", "Recorder-Diagnose",
                "/replay status (Recorder-Interna).", DefaultGrant.ADMIN_ONLY);

        // --- Dashboard / web ---
        register("eternal.web.dashboard", "web", "Dashboard öffnen",
                "Darf das Web-Dashboard betreten.", DefaultGrant.STAFF_ANY);
        register("eternal.web.player.view", "web", "Spieler-Lookup",
                "Darf Spieler-Profile im Dashboard sehen.", DefaultGrant.STAFF_ANY);
        register("eternal.web.appeals.decide", "web", "Anträge entscheiden",
                "Darf Entbannungsanträge approve/deny/shorten.", DefaultGrant.ADMIN_ONLY,
                List.of("eternal.web.dashboard"), List.of());
        register("eternal.web.admin", "web", "Admin-Panel",
                "Darf das Admin-Panel + Rollenverwaltung.", DefaultGrant.ADMIN_ONLY);
        register("eternal.web.chatlogs", "web", "Chat-Logs",
                "Darf die netzwerkweiten Chat-Logs im Dashboard durchsuchen.", DefaultGrant.STAFF_ANY);
        register("eternal.web.chatlogs.sensitive", "web", "Sensible Chat-Logs",
                "Darf Login/Register/Passwort-Befehle einsehen.", DefaultGrant.ADMIN_ONLY,
                List.of("eternal.web.chatlogs"), List.of());

        // --- Generic notify ---
        register("eternal.notify", "notify", "Mod-Broadcasts",
                "Empfängt Broadcasts bei Bans/Mutes.", DefaultGrant.STAFF_ANY);

        // --- Team / self-service ---
        // Marks a user as a team member who may request permissions via the
        // self-service access-request page. Gewährt selbst nichts ausser dem
        // Zugang zur Bestell-Seite — Admin vergibt ihn an die Team-Ränge.
        register("eternal.team", "team", "Teammitglied",
                "Darf sich auf der Bestell-Seite Rechte anfragen.", DefaultGrant.STAFF_ANY);

        // --- Autonicker ---
        register("eternal.autonick.use", "autonick", "Nicken",
                "Darf sich mit dem Autonicker tarnen (Nick-Item + /autonick).", DefaultGrant.STAFF_ANY);
    }

    /** Add a per-reason key — generated when {@code reasons.yml} is loaded.
     *  Idempotent: re-registering an existing key keeps the first
     *  registration's metadata so admin UI labels stay stable.
     *
     *  <p>The key gates the <b>web report</b> ban/mute flow: a reason is only
     *  usable (and only shown in the dashboard dialog) when the principal holds
     *  this key in addition to the base perm. Default is {@code STAFF_ANY}
     *  (opt-out) — every ban/mute holder may use every reason until an admin
     *  flips a specific reason to "Aus" for a role. In-game enforcement keeps
     *  using {@code PunishmentReason.effectivePermission()} + tier as before.</p>
     *
     *  @param type      ban vs mute — decides the base perm shown as a dependency.
     *  @param adminOnly whether the reason is an admin reason ({@code eternal.ban.admin}).
     */
    public void registerReasonScoped(int reasonId, @NotNull String reasonLabel,
                                     @NotNull de.eternal.core.model.PunishmentType type,
                                     boolean adminOnly) {
        String base = type == de.eternal.core.model.PunishmentType.MUTE
                ? "eternal.mute"
                : (adminOnly ? "eternal.ban.admin" : "eternal.ban");
        String kind = type == de.eternal.core.model.PunishmentType.MUTE ? "Mute" : "Bann";
        register("eternal.ban.reason." + reasonId, "reasons",
                kind + "-Grund #" + reasonId + ": " + reasonLabel,
                "Darf diesen " + kind + "-Grund im Web-Report verwenden.",
                DefaultGrant.STAFF_ANY, List.of(base), List.of());
    }

    public void register(@NotNull String key, @NotNull String category,
                          @NotNull String label, @NotNull String description,
                          @NotNull DefaultGrant defaultGrant) {
        register(key, category, label, description, defaultGrant, List.of(), List.of());
    }

    public void register(@NotNull String key, @NotNull String category,
                          @NotNull String label, @NotNull String description,
                          @NotNull DefaultGrant defaultGrant,
                          @NotNull List<String> requires,
                          @NotNull List<String> relatedTo) {
        byKey.putIfAbsent(key,
                new Entry(key, category, label, description, defaultGrant, requires, relatedTo));
    }

    public @NotNull List<Entry> entries() {
        return List.copyOf(byKey.values());
    }

    public @NotNull Map<String, List<Entry>> byCategory() {
        Map<String, List<Entry>> out = new LinkedHashMap<>();
        for (Entry e : byKey.values()) {
            out.computeIfAbsent(e.category, k -> new java.util.ArrayList<>()).add(e);
        }
        // Freeze the per-category lists so callers can't mutate the registry's state.
        out.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return Collections.unmodifiableMap(out);
    }

    public @org.jetbrains.annotations.Nullable Entry find(@NotNull String key) {
        return byKey.get(key);
    }

    public boolean knows(@NotNull String key) {
        return byKey.containsKey(key);
    }
}
