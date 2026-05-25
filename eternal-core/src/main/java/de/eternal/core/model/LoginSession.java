package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record LoginSession(
        long id,
        @NotNull UUID uuid,
        @NotNull String name,
        @NotNull String ip,
        @NotNull Instant loginAt,
        @Nullable Instant logoutAt
) {
    public boolean isActive() {
        return logoutAt == null;
    }
}
