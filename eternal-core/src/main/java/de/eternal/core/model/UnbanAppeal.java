package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record UnbanAppeal(
        long id,
        long banId,
        @NotNull UUID applicantUuid,
        @NotNull String applicantName,
        @NotNull String text,
        @NotNull Status status,
        @NotNull Instant createdAt,
        @Nullable UUID reviewerUuid,
        @Nullable String reviewerName,
        @Nullable Instant reviewedAt,
        @Nullable String decisionReason
) {
    public enum Status {
        PENDING,
        APPROVED,
        DENIED
    }
}
