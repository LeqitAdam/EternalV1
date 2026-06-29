package de.eternal.core.maintenance;

import de.eternal.core.config.Configs;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Typed view of the {@code log-cleanup:} config section. Drives the periodic
 * deletion of old server log files (the {@code logs/} folder fills up over time
 * with rotated {@code *.log.gz}). Same config on Spigot + the proxy.
 */
public record LogCleanupConfig(
        boolean enabled,
        @NotNull String directory,
        int maxAgeDays,
        int intervalMinutes,
        @NotNull List<String> patterns,
        @NotNull List<String> keep
) {
    public static @NotNull LogCleanupConfig fromMap(@NotNull Map<String, Object> raw) {
        return new LogCleanupConfig(
                Configs.boolOr(raw, "enabled", true),
                Configs.stringOr(raw, "directory", "logs"),
                Math.max(1, Configs.intOr(raw, "max-age-days", 7)),
                Math.max(5, Configs.intOr(raw, "interval-minutes", 120)),
                Configs.stringListOr(raw, "patterns", List.of("*.log.gz", "*.log", "proxy.log.*")),
                Configs.stringListOr(raw, "keep", List.of("latest.log"))
        );
    }

    public static @NotNull LogCleanupConfig defaults() {
        return fromMap(Map.of());
    }
}
