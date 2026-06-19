package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A self-service access request: a team member ({@code eternal.team}) asks for a
 * single permission key, an admin approves or denies it. On approval the key is
 * written as a personal user-override for the requester, optionally with an
 * expiry. Serialized to JSON for the dashboard via {@code Json.GSON}.
 */
public record PermissionRequest(
        long id,
        @NotNull UUID requesterUuid,
        @NotNull String requesterName,
        @NotNull String permissionKey,
        @Nullable String justification,
        @NotNull Status status,
        @NotNull Instant createdAt,
        @Nullable UUID decidedByUuid,
        @Nullable String decidedByName,
        @Nullable Instant decidedAt,
        @Nullable String decisionNote,
        /** When the granted override expires (set on approval with a duration);
         *  {@code null} = permanent. */
        @Nullable Instant expiresAt
) {
    public enum Status { PENDING, APPROVED, DENIED, EXPIRED }
}
