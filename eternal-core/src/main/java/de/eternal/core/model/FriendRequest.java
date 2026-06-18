package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A pending (or resolved) friend request from one player to another. Mirrors
 * the {@link LinkCode}/{@link UnbanAppeal} style: enum status stored as
 * VARCHAR, nullable {@code respondedAt} once it leaves PENDING.
 */
public record FriendRequest(
        long id,
        @NotNull UUID fromUuid,
        @NotNull String fromName,
        @NotNull UUID toUuid,
        @NotNull String toName,
        @NotNull Status status,
        @NotNull Instant createdAt,
        @Nullable Instant respondedAt
) {
    public enum Status { PENDING, ACCEPTED, DECLINED, CANCELLED }
}
