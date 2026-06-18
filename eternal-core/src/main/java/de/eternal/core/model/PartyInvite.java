package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * An outstanding invite into a {@link Party}. {@code expiresAt} mirrors the
 * punishment {@code expires_at} pattern — a pending invite past its expiry is
 * treated as gone (filtered with {@code expires_at > now}).
 */
public record PartyInvite(
        long id,
        long partyId,
        @NotNull UUID fromUuid,
        @NotNull String fromName,
        @NotNull UUID toUuid,
        @NotNull String toName,
        @NotNull Status status,
        @NotNull Instant createdAt,
        @Nullable Instant expiresAt
) {
    public enum Status { PENDING, ACCEPTED, DECLINED, EXPIRED, CANCELLED }
}
