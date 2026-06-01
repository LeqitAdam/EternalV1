package de.eternal.core.permission;

import de.eternal.core.model.PermissionGrant;
import de.eternal.core.model.Role;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read/write surface for the three permission tables. The
 * {@link de.eternal.core.storage.sql.SqlStorage SQL storage} implements
 * this directly; callers should treat it as an opaque DAO.
 *
 * <p>All methods are safe to call from any thread — the underlying
 * Hikari pool handles concurrency.</p>
 */
public interface PermissionStorage {

    /* --- roles ---------------------------------------------------------- */

    @NotNull List<Role> listRoles();

    @NotNull Optional<Role> findRole(@NotNull String name);

    @NotNull Optional<Role> findRoleByMcGroup(@NotNull String mcGroupName);

    /** Insert or update a role row. {@code createdAt} on the input is
     *  ignored when updating — the original timestamp is preserved. */
    void upsertRole(@NotNull Role role);

    boolean deleteRole(@NotNull String name);

    /* --- role permissions ---------------------------------------------- */

    /** All role-level grants for one role, keyed by permission key. */
    @NotNull Map<String, PermissionGrant> rolePermissions(@NotNull String roleName);

    /** Set or update the role's setting for one permission key. */
    void setRolePermission(@NotNull String roleName, @NotNull String key,
                           boolean granted, @Nullable String updatedBy);

    /** Remove the role's setting for one key — falls back to the
     *  hardcoded default on the next resolve. Returns true if a row
     *  was actually deleted. */
    boolean clearRolePermission(@NotNull String roleName, @NotNull String key);

    /* --- user permissions ---------------------------------------------- */

    @NotNull Map<String, PermissionGrant> userPermissions(@NotNull UUID userUuid);

    void setUserPermission(@NotNull UUID userUuid, @NotNull String key,
                            boolean granted, @Nullable String updatedBy);

    boolean clearUserPermission(@NotNull UUID userUuid, @NotNull String key);
}
