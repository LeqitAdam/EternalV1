package de.eternal.core.config;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Typed view of the {@code autonicker:} section of config.yml (Spigot-only
 * module, but kept in core for reuse + testability). Drives the nametag item,
 * the random name/skin pools and — on CloudNet only — the random non-team
 * rank selection.
 *
 * <p>{@code nickableGroups} is the allowlist of CloudNet groups the random
 * rank may pick from. When empty, the randomiser falls back to "every group
 * from CloudNet that is NOT in {@code protectedGroups}", so "always hide the
 * youtube rank" is honoured by listing it as protected.</p>
 */
public record AutonickerConfig(
        boolean enabled,
        int nametagSlot,
        boolean giveTagOnJoin,
        boolean lockTagSlot,
        boolean randomizeRank,
        boolean hideRankWithoutCloudnet,
        @NotNull String defaultDisplay,
        boolean appendRandomDigits,
        @NotNull List<String> namePool,
        @NotNull List<String> skinPool,
        @NotNull List<String> nickableGroups,
        @NotNull List<String> protectedGroups,
        boolean network,
        boolean reconnectOnNick
) {
    // Realistic, unremarkable player-style names — a disguise should look like a
    // normal player, not "Enderman"/"Steve5". Edit name-pool in config.yml to taste.
    private static final List<String> DEFAULT_NAMES = List.of(
            "Leon", "Finn", "Luca", "Jonas", "Max", "Tim", "Ben", "Paul",
            "Niklas", "Felix", "Julian", "Moritz", "Elias", "Noah", "David",
            "Simon", "Jan", "Nico", "Lars", "Marvin", "Kevin", "Dennis",
            "Robin", "Fabian", "Mats", "Jannik", "Erik", "Tom", "Liam", "Henri");
    private static final List<String> DEFAULT_SKINS = List.of(
            "Notch", "jeb_", "Dinnerbone", "Grumm", "Steve", "Alex");
    private static final List<String> DEFAULT_PROTECTED = List.of(
            "owner", "admin", "sradmin", "srmod", "mod", "moderator", "supporter",
            "builder", "developer", "dev", "team", "staff", "youtuber", "youtube", "yt");

    public static @NotNull AutonickerConfig fromMap(@NotNull Map<String, Object> raw) {
        int slot1Based = Configs.intOr(raw, "nametag-slot", 5);
        int slot = Math.max(0, Math.min(8, slot1Based - 1));
        return new AutonickerConfig(
                Configs.boolOr(raw, "enabled", true),
                slot,
                Configs.boolOr(raw, "give-tag-on-join", true),
                Configs.boolOr(raw, "lock-tag-slot", true),
                Configs.boolOr(raw, "randomize-rank", true),
                Configs.boolOr(raw, "hide-rank-without-cloudnet", true),
                Configs.stringOr(raw, "default-display", "&7{name}"),
                Configs.boolOr(raw, "append-random-digits", false),
                lowerAll(Configs.stringListOr(raw, "name-pool", DEFAULT_NAMES), false),
                Configs.stringListOr(raw, "skin-pool", DEFAULT_SKINS),
                lowerAll(Configs.stringListOr(raw, "nickable-groups", List.of()), true),
                lowerAll(Configs.stringListOr(raw, "protected-groups", DEFAULT_PROTECTED), true),
                // network=true → the BungeeCord proxy owns nick state and pushes
                // the disguise to each backend (skin works network-wide + survives
                // server switches). false → each Spigot server nicks locally.
                Configs.boolOr(raw, "network", false),
                // Fallback nur fuer NICHT-Paper-Backends MIT >=2 Servern: Lobby-
                // Bounce-Reconnect fuer die Eigen-Sicht. Auf Paper unnoetig
                // (setPlayerProfile refresht server-intern) → Standard aus.
                Configs.boolOr(raw, "reconnect-on-nick", false)
        );
    }

    public static @NotNull AutonickerConfig defaults() {
        return fromMap(Map.of());
    }

    private static @NotNull List<String> lowerAll(@NotNull List<String> in, boolean lower) {
        if (!lower) return List.copyOf(in);
        return in.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }
}
