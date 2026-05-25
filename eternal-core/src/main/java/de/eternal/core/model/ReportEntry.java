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
        @Nullable String resolution
) {
}
