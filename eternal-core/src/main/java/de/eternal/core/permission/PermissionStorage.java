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

    /** The in-game CloudPerms nodes mirrored for one CloudNet group, keyed by
     *  permission key. Synced from the proxy; resolved with priority over the
     *  web role grants (but under user overrides). Empty when none synced. */
    @NotNull Map<String, PermissionGrant> cloudGroupPermissions(@NotNull String group);

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

    /** Same, but with an optional expiry (epoch ms) — used by approved access
     *  requests. {@code null} = permanent. */
    void setUserPermission(@NotNull UUID userUuid, @NotNull String key, boolean granted,
                            @Nullable String updatedBy, @Nullable Long expiresAtMs);

    boolean clearUserPermission(@NotNull UUID userUuid, @NotNull String key);

    /* --- access requests ----------------------------------------------- */

    /** Insert a PENDING request; returns the new id. */
    long createPermissionRequest(@NotNull UUID requesterUuid, @NotNull String requesterName,
                                 @NotNull String permissionKey, @Nullable String justification);

    @NotNull Optional<de.eternal.core.model.PermissionRequest> findPermissionRequest(long id);

    /** All requests with the given status, newest first; {@code null} = all. */
    @NotNull List<de.eternal.core.model.PermissionRequest> listPermissionRequests(
            @Nullable de.eternal.core.model.PermissionRequest.Status status);

    /** This user's own requests, newest first. */
    @NotNull List<de.eternal.core.model.PermissionRequest> myPermissionRequests(@NotNull UUID requesterUuid);

    /** True if the user already has a PENDING request for this exact key. */
    boolean hasPendingRequest(@NotNull UUID requesterUuid, @NotNull String permissionKey);

    /** Records the decision on a PENDING request (APPROVED/DENIED). {@code expiresAtMs}
     *  is stored for audit on approvals. Returns true if a pending row matched. */
    boolean decidePermissionRequest(long id, @NotNull UUID byUuid, @NotNull String byName,
                                    @NotNull de.eternal.core.model.PermissionRequest.Status status,
                                    @Nullable String note, @Nullable Long expiresAtMs);

    /** Deletes expired user-permission grants and flips their APPROVED requests
     *  to EXPIRED. Returns the number of grants removed. */
    int sweepExpiredUserGrants();
}
