package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A network-wide party. Authoritative state lives in the DB so a party can
 * span backends; {@code disbandedAt != null} is the soft-end marker (mirrors
 * report closure) so the row survives for audit while reading as "gone".
 */
public record Party(
        long id,
        @NotNull UUID leaderUuid,
        @NotNull String leaderName,
        @NotNull Instant createdAt,
        @Nullable Instant disbandedAt
) {
    public boolean active() {
        return disbandedAt == null;
    }
}
