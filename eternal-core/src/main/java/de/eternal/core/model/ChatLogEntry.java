package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * One logged chat/command/msg line. Persisted to {@code eternal_chat_log}
 * (or {@code eternal_sensitive_log} for sensitive commands) and serialized to
 * JSON for the dashboard via {@code Json.GSON} (Instant -> epoch millis).
 *
 * <p>{@code targetUuid}/{@code targetName} are only set for {@link ChatLogKind#MSG}
 * (the recipient of a private message); they are {@code null} otherwise.</p>
 */
public record ChatLogEntry(
        long id,
        @NotNull ChatLogKind kind,
        @NotNull String server,
        @NotNull String senderUuid,
        @NotNull String senderName,
        @Nullable String targetUuid,
        @Nullable String targetName,
        @NotNull String content,
        @NotNull Instant createdAt
) {
}
