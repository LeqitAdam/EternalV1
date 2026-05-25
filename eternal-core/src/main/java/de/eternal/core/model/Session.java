package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

public record Session(
        @NotNull String token,
        @NotNull UUID userUuid,
        @NotNull String userName,
        @NotNull String role,
        @NotNull Instant createdAt,
        @NotNull Instant expiresAt
) {
    public boolean isExpired(@NotNull Instant now) {
        return now.isAfter(expiresAt);
    }
}
