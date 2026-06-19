package de.eternal.core.permission;

import de.eternal.core.model.PermissionGrant;
import de.eternal.core.model.PlayerProfile;
import de.eternal.core.model.Role;
import de.eternal.core.storage.EternalStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
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
     * @param uuid the user's UUID, or {@code null} for system-key
     *             principals not bound to a player
     * @param fullAccess {@code true} for trusted static API-key principals
     *                   (api.yml) — they bypass the grant chain entirely and
     *                   are allowed everything. Web sessions are always
     *                   {@code false} and resolve purely through the grants.
     */
    public record Principal(@Nullable UUID uuid, boolean fullAccess) {
        public Principal(@Nullable UUID uuid) { this(uuid, false); }
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
        // Trusted static API keys bypass the chain — they are server-config
        // secrets and always allowed (also the guaranteed no-lockout path).
        if (principal.fullAccess()) return true;

        // Path 1 — user override. Only meaningful when the principal has an
        // associated UUID. Honours wildcard grants (* / eternal.* / ...).
        if (principal.uuid() != null) {
            Boolean userGrant = resolve(permStorage.userPermissions(principal.uuid()), key);
            if (userGrant != null) return userGrant;
        }

        // The in-game + web-role layers both key off the user's current CloudNet
        // group — one profile lookup for both.
        String group = groupOf(principal);
        if (group != null) {
            // Path 2 — in-game CloudPerms (synced from the proxy), PRIORITIZED
            // over the web role config. A negative in-game node (granted=false)
            // or a wildcard (* / eternal.*) resolves here.
            Boolean ig = resolve(permStorage.cloudGroupPermissions(group), key);
            if (ig != null) return ig;

            // Path 3 — web role setting (dashboard editor) for the matching role.
            Optional<Role> role = permStorage.findRoleByMcGroup(group);
            if (role.isPresent()) {
                Boolean roleGrant = resolve(permStorage.rolePermissions(role.get().name()), key);
                if (roleGrant != null) return roleGrant;
            }
        }

        // Path 4 — hardcoded default. Legacy roles are gone, so ADMIN_ONLY /
        // STAFF_ANY can no longer grant implicitly (they require an explicit
        // grant or a covering wildcard). Only EVERYONE grants by default;
        // everything else — including unknown keys — denies.
        PermissionRegistry.Entry entry = registry.find(key);
        if (entry == null) return false;
        return entry.defaultGrant() == PermissionRegistry.DefaultGrant.EVERYONE;
    }

    /** The principal's current CloudNet group name, or {@code null} when no
     *  UUID / profile / group. Drives both the in-game and web-role layers. */
    private @Nullable String groupOf(@NotNull Principal principal) {
        if (principal.uuid() == null) return null;
        return storage.findProfile(principal.uuid())
                .map(PlayerProfile::lastGroupName)
                .filter(g -> g != null && !g.isEmpty())
                .orElse(null);
    }

    /**
     * Resolve {@code key} against a grant map, honouring wildcards. Returns
     * {@code true}/{@code false} for the most specific matching grant, or
     * {@code null} when the map has no opinion (fall through to the next layer).
     *
     * <p>Specificity order (first match wins, so a precise deny overrides a
     * broad grant): exact key → {@code prefix.*} from longest prefix down →
     * {@code *}. So {@code eternal.web.admin} is matched by, in order,
     * {@code eternal.web.admin}, {@code eternal.web.*}, {@code eternal.*},
     * {@code *}.</p>
     */
    private static @Nullable Boolean resolve(@NotNull Map<String, PermissionGrant> grants, @NotNull String key) {
        if (grants.isEmpty()) return null;
        PermissionGrant g = active(grants.get(key));
        if (g != null) return g.granted();
        String prefix = key;
        int dot;
        while ((dot = prefix.lastIndexOf('.')) >= 0) {
            prefix = prefix.substring(0, dot);
            g = active(grants.get(prefix + ".*"));
            if (g != null) return g.granted();
        }
        g = active(grants.get("*"));
        return g != null ? g.granted() : null;
    }

    /** A grant only counts while unexpired; an expired one resolves like an
     *  absent row (fall through to the next layer). Used by approved access
     *  requests that carry an {@code expires_at}. */
    private static @Nullable PermissionGrant active(@Nullable PermissionGrant g) {
        if (g == null) return null;
        return (g.expiresAt() != null && !g.expiresAt().isAfter(java.time.Instant.now())) ? null : g;
    }

    /**
     * Bulk variant of {@link #has}: of {@code keys}, returns the subset the
     * principal holds. Fetches the user + role grant maps ONCE (instead of
     * per-key), so the dashboard's {@code /me} capability list is a couple of
     * queries rather than two per key. Wildcards + defaults resolve exactly like
     * {@link #has}.
     */
    public @NotNull java.util.Set<String> grantedAmong(@NotNull Principal principal,
                                                        @NotNull java.util.Collection<String> keys) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (principal.fullAccess()) { out.addAll(keys); return out; }
        Map<String, PermissionGrant> userGrants = principal.uuid() != null
                ? permStorage.userPermissions(principal.uuid()) : Map.of();
        String group = groupOf(principal);
        Map<String, PermissionGrant> cloudGrants = group != null
                ? permStorage.cloudGroupPermissions(group) : Map.of();
        Optional<Role> role = group != null ? permStorage.findRoleByMcGroup(group) : Optional.empty();
        Map<String, PermissionGrant> roleGrants = role.isPresent()
                ? permStorage.rolePermissions(role.get().name()) : Map.of();
        for (String key : keys) {
            Boolean u = resolve(userGrants, key);
            if (u != null) { if (u) out.add(key); continue; }
            Boolean ig = resolve(cloudGrants, key);
            if (ig != null) { if (ig) out.add(key); continue; }
            Boolean r = resolve(roleGrants, key);
            if (r != null) { if (r) out.add(key); continue; }
            PermissionRegistry.Entry e = registry.find(key);
            if (e != null && e.defaultGrant() == PermissionRegistry.DefaultGrant.EVERYONE) out.add(key);
        }
        return out;
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
