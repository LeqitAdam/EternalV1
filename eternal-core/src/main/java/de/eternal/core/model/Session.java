package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A web dashboard session. Authorization is permission-based (see
 * {@link de.eternal.core.permission.PermissionService}) — sessions carry no
 * role any more; capabilities derive from the user's CloudNet group/role grants.
 */
public record Session(
        @NotNull String token,
        @NotNull UUID userUuid,
        @NotNull String userName,
        @NotNull Instant createdAt,
        @NotNull Instant expiresAt
) {
    public boolean isExpired(@NotNull Instant now) {
        return now.isAfter(expiresAt);
    }
}
