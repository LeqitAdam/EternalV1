package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record ReportEntry(
        long id,
        @NotNull UUID reporterUuid,
        @NotNull String reporterName,
        @NotNull UUID targetUuid,
        @NotNull String targetName,
        @NotNull String reasonId,
        @NotNull String reasonLabel,
        @Nullable String comment,
        @NotNull String serverName,
        @NotNull Instant createdAt,
        @NotNull ReportStatus status,
        @Nullable UUID handlerUuid,
        @Nullable String handlerName,
        @Nullable Instant claimedAt,
        @Nullable Instant closedAt,
        @Nullable String resolution,
        /** Replay-Id attached to this report (set by ReplayBridge after
         *  endCaptureBlocking persists the file). Null when no replay
         *  exists yet — most commonly because the in-flight capture is
         *  still running, but also for reports that pre-date the replay
         *  system or where the recorder was idle. Surfaced in /history
         *  so staff can /replay play <id> the recording later. */
        @Nullable Long replayId,
        /** JSON snapshot (array of {@link ChatLogEntry}) of the chat context
         *  around this report, persisted once the +after window has closed.
         *  Null until finalized — the chat endpoint computes it live and only
         *  writes it back via {@code linkReportChatHistory} when the window
         *  has elapsed. Tolerated as a missing column on un-migrated DBs. */
        @Nullable String chatHistory
) {
}
