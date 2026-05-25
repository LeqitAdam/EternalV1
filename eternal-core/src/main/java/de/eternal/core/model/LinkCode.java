package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record LinkCode(
        @NotNull String code,
        @NotNull String linkToken,
        @NotNull Status status,
        @Nullable UUID confirmedUuid,
        @Nullable String confirmedName,
        @NotNull Instant createdAt,
        @NotNull Instant expiresAt
) {
    public enum Status {
        PENDING,
        CONFIRMED,
        CONSUMED,
        EXPIRED
    }
}
