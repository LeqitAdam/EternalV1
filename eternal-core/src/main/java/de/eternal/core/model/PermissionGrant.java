package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * One {@code (key → granted)} entry in either {@code
 * eternal_role_permissions} or {@code eternal_user_permissions}. The
 * {@link #subject} is either a role name or a UUID string depending on
 * which table the row came from — the wrapper class is shared because
 * the read/write code is symmetric.
 *
 * <p>{@link #granted} resolves to a tri-state with the database:
 * <ul>
 *     <li>row exists with granted=true  → permission GRANTED</li>
 *     <li>row exists with granted=false → permission DENIED (overrides
 *         the hardcoded default for that key)</li>
 *     <li>row missing                   → fall through to next layer
 *         (user → role → hardcoded default)</li>
 * </ul>
 * </p>
 */
public record PermissionGrant(
        @NotNull String subject,
        @NotNull String permissionKey,
        boolean granted,
        @NotNull Instant updatedAt,
        @Nullable String updatedBy
) {
}
