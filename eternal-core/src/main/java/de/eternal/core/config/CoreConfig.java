package de.eternal.core.config;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Map;

public record CoreConfig(
        @NotNull String serverName,
        @NotNull DatabaseConfig database,
        @NotNull ReportSettings reports,
        @NotNull HistorySettings history,
        @NotNull ChatlogConfig chatlog
) {

    public record ReportSettings(
            int cooldownSeconds,
            int maxOpenPerReporter,
            boolean notifyOnlineStaff,
            boolean teleportOnClaim
    ) {
        public static @NotNull ReportSettings fromMap(@NotNull Map<String, Object> raw) {
            return new ReportSettings(
                    Configs.intOr(raw, "cooldown-seconds", 30),
                    Configs.intOr(raw, "max-open-per-reporter", 3),
                    Configs.boolOr(raw, "notify-online-staff", true),
                    Configs.boolOr(raw, "teleport-on-claim", true)
            );
        }
    }

    /**
     * Behaviour of {@code /resethistory}. {@code hard} deletes the rows
     * outright; {@code soft} flips a {@code hidden=1} flag so the data is
     * recoverable via direct DB access but invisible to /history and to the
     * escalation counter.
     */
    public record HistorySettings(boolean hardReset) {
        public static @NotNull HistorySettings fromMap(@NotNull Map<String, Object> raw) {
            String mode = Configs.stringOr(raw, "reset-mode", "hard").toLowerCase();
            return new HistorySettings(!mode.startsWith("soft"));
        }
    }

    public static @NotNull CoreConfig fromMap(@NotNull Map<String, Object> raw, @NotNull Path dataFolder) {
        return new CoreConfig(
                Configs.stringOr(raw, "server-name", "lobby"),
                DatabaseConfig.fromMap(Configs.sectionOr(raw, "database"), dataFolder),
                ReportSettings.fromMap(Configs.sectionOr(raw, "reports")),
                HistorySettings.fromMap(Configs.sectionOr(raw, "history")),
                ChatlogConfig.fromMap(Configs.sectionOr(raw, "chatlog"), dataFolder)
        );
    }
}
