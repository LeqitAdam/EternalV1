package de.eternal.core.config;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The {@code chatlog:} config section. Powers the chat-log + social-spy
 * feature: DB-backed search/history plus per-server flat files. Resolved
 * once from the platform YAML and threaded through to the spigot writer +
 * the API report-context window.
 *
 * <p>{@code directory} is resolved against the plugin dataFolder exactly
 * like {@link DatabaseConfig} resolves its SQLite file, so callers get an
 * absolute {@link Path}.</p>
 */
public record ChatlogConfig(
        boolean enabled,
        @NotNull Path directory,
        boolean logChat,
        boolean logCommands,
        int reportContextBefore,
        int reportContextAfter,
        int reportContextWindowSeconds,
        int flushIntervalTicks,
        @NotNull List<String> sensitiveCommands,
        boolean socialspyNetwork
) {

    private static final List<String> DEFAULT_SENSITIVE = List.of(
            "login", "l", "register", "reg",
            "changepassword", "changepass", "2fa", "authme"
    );

    public static @NotNull ChatlogConfig fromMap(@NotNull Map<String, Object> raw, @NotNull Path dataFolder) {
        return new ChatlogConfig(
                Configs.boolOr(raw, "enabled", true),
                dataFolder.resolve(Configs.stringOr(raw, "directory", "chatlogs")),
                Configs.boolOr(raw, "log-chat", true),
                Configs.boolOr(raw, "log-commands", true),
                Configs.intOr(raw, "report-context-before", 10),
                Configs.intOr(raw, "report-context-after", 10),
                Configs.intOr(raw, "report-context-window-seconds", 120),
                Configs.intOr(raw, "flush-interval-ticks", 40),
                Configs.stringListOr(raw, "sensitive-commands", DEFAULT_SENSITIVE),
                Configs.boolOr(raw, "socialspy-network", true)
        );
    }
}
