package de.eternal.core.permission;

import de.eternal.core.model.PermissionGrant;
import de.eternal.core.model.PlayerProfile;
import de.eternal.core.model.Role;
import de.eternal.core.storage.EternalStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * The actual "is this user allowed to do X?" resolver. Wraps
 * {@link PermissionStorage} (the DAO) and {@link PermissionRegistry}
 * (the catalogue of defaults) with the three-step resolution chain
 * the dashboard exposes to the admin:
 *
 * <ol>
 *     <li><b>User override</b> — row in
 *         {@code eternal_user_permissions} for this UUID and key. Wins
 *         absolutely, both for "explicit grant" and "explicit deny".</li>
 *     <li><b>Role setting</b> — row in
 *         {@code eternal_role_permissions} for the role tied to the
 *         user's current CloudNet group.</li>
 *     <li><b>Hardcoded default</b> — {@link PermissionRegistry.DefaultGrant}
 *         on the catalogue entry. ADMIN_ONLY requires the principal's
 *         legacy role to be ADMIN, STAFF_ANY requires staff (any
 *         non-PLAYER role), EVERYONE always grants, NEVER always
 *         denies.</li>
 * </ol>
 *
 * <p>Resolution is the hot path on every API call, so we keep it
 * branch-free and avoid allocations. Caching can be added later — for
 * now Hikari + the indexed primary keys are fast enough.</p>
 */
public final class PermissionService {

    private final PermissionStorage permStorage;
    private final EternalStorage storage;
    private final PermissionRegistry registry;

    public PermissionService(@NotNull PermissionStorage permStorage,
                              @NotNull EternalStorage storage,
                              @NotNull PermissionRegistry registry) {
        this.permStorage = permStorage;
        this.storage = storage;
        this.registry = registry;
    }

    public @NotNull PermissionRegistry registry() {
        return registry;
    }

    /**
     * The principal we resolve against. Built from either an API-key
     * row, a web session, or a freshly-joined player. Carries enough
     * context to apply the three-step chain without further DB hits
     * for the common case where the user has no override.
     *
     * @param uuid the user's UUID, or {@code null} for legacy
     *             system-key principals not bound to a player
     * @param legacyRole "ADMIN" / "MOD" / "PLAYER" — used by the
     *                   hardcoded-default tier (ADMIN_ONLY / STAFF_ANY)
     */
    public record Principal(@Nullable UUID uuid, @NotNull String legacyRole) {
        public boolean isAdmin() { return "ADMIN".equalsIgnoreCase(legacyRole); }
        public boolean isStaff() { return isAdmin() || "MOD".equalsIgnoreCase(legacyRole); }
    }

    /**
     * The big one: is {@code principal} allowed to use permission
     * {@code key}? See the class javadoc for resolution order.
     *
     * <p>Returns {@code false} for unknown keys — callers can pre-
     * validate against {@link PermissionRegistry#knows} if they need
     * to distinguish "denied by policy" from "typo'd key".</p>
     */
    public boolean has(@NotNull Principal principal, @NotNull String key) {
        // Path 1 — user override. Only meaningful when the principal
        // has an associated UUID (API-key admins without UUID skip
        // this layer and fall straight to the role / default check).
        if (principal.uuid() != null) {
            PermissionGrant userGrant = permStorage.userPermissions(principal.uuid()).get(key);
            if (userGrant != null) return userGrant.granted();
        }

        // Path 2 — role setting. Look up the principal's current
        // CloudNet group (cached on their profile as last_group_name)
        // and check the role that points at it.
        Optional<Role> role = roleOf(principal);
        if (role.isPresent()) {
            PermissionGrant roleGrant = permStorage.rolePermissions(role.get().name()).get(key);
            if (roleGrant != null) return roleGrant.granted();
        }

        // Path 3 — hardcoded default. Honour the catalogue entry's
        // policy. If the key isn't registered at all we deny by
        // default — caller probably has a typo.
        PermissionRegistry.Entry entry = registry.find(key);
        if (entry == null) return false;
        return switch (entry.defaultGrant()) {
            case NEVER       -> false;
            case ADMIN_ONLY  -> principal.isAdmin();
            case STAFF_ANY   -> principal.isStaff();
            case EVERYONE    -> true;
        };
    }

    /** Resolve the role tied to this principal via their cached
     *  CloudNet group. Returns empty when the principal has no UUID,
     *  no profile, no group, or no role row matches the group. */
    public @NotNull Optional<Role> roleOf(@NotNull Principal principal) {
        if (principal.uuid() == null) return Optional.empty();
        Optional<PlayerProfile> profile = storage.findProfile(principal.uuid());
        if (profile.isEmpty()) return Optional.empty();
        String group = profile.get().lastGroupName();
        if (group == null || group.isEmpty()) return Optional.empty();
        return permStorage.findRoleByMcGroup(group);
    }
}
