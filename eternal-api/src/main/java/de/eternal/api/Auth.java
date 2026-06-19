package de.eternal.api;

import de.eternal.core.model.Session;
import de.eternal.core.permission.PermissionService;
import de.eternal.core.storage.EternalStorage;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.UnauthorizedResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bearer-token authentication. Accepts either:
 *  - A static API key from the api.yml file (full access — trusted secret), or
 *  - A web session token issued via the /auth/link flow (stored in DB).
 *
 * Legacy ADMIN/MOD/PLAYER roles are gone: every action is gated by an
 * {@code eternal.*} permission via {@link PermissionService}, resolved from the
 * user's CloudNet group/role grants. Static API keys bypass that chain
 * ({@code fullAccess}).
 *
 * The "internal" shared secret is checked separately for plugin->API calls
 * via the X-Internal-Secret header.
 */
public final class Auth {

    /**
     * Logical principal — a static API key or a DB-backed web session.
     *
     * @param fullAccess {@code true} only for static API keys (trusted
     *                   server-config secrets); web sessions are always
     *                   permission-resolved.
     */
    public record Principal(@NotNull String name, @Nullable UUID uuid, boolean fullAccess) {
    }

    private final Map<String, ApiConfig.ApiKey> byKey = new HashMap<>();
    private final EternalStorage storage;
    private final String internalSecret;
    private final PermissionService permissions;

    public Auth(@NotNull List<ApiConfig.ApiKey> keys,
                @NotNull EternalStorage storage,
                @NotNull String internalSecret,
                @NotNull PermissionService permissions) {
        for (var k : keys) byKey.put(k.key(), k);
        this.storage = storage;
        this.internalSecret = internalSecret;
        this.permissions = permissions;
    }

    /** Exposed so Routes can use the same service for explicit
     *  permission checks (e.g. when checking reason-scoped ban perms
     *  before queuing the ban action). */
    public @NotNull PermissionService permissions() { return permissions; }

    public @Nullable Principal resolve(@NotNull Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        String token = header.substring("Bearer ".length()).trim();

        ApiConfig.ApiKey staticKey = byKey.get(token);
        if (staticKey != null) {
            return new Principal(staticKey.name(), staticKey.uuid(), true);
        }
        return storage.findSession(token)
                .map(s -> new Principal(s.userName(), s.userUuid(), false))
                .orElse(null);
    }

    public @NotNull Principal require(@NotNull Context ctx) {
        Principal p = resolve(ctx);
        if (p == null) throw new UnauthorizedResponse("Bearer token missing or invalid");
        return p;
    }

    /** Does {@code p} hold permission {@code key}? Static API keys (fullAccess)
     *  always do; sessions resolve through their CloudNet role grants. */
    public boolean can(@NotNull Principal p, @NotNull String key) {
        return permissions.has(new PermissionService.Principal(p.uuid(), p.fullAccess()), key);
    }

    /** Of {@code keys}, the subset {@code p} holds — one cheap bulk resolve for
     *  the {@code /me} capability list. */
    public @NotNull java.util.Set<String> capabilities(@NotNull Principal p,
                                                       @NotNull java.util.Collection<String> keys) {
        return permissions.grantedAmong(new PermissionService.Principal(p.uuid(), p.fullAccess()), keys);
    }

    /**
     * The single authorization gate. Resolves the principal, then asks
     * {@link PermissionService} whether they hold {@code key}
     * (user override → CloudNet role grant → hardcoded default, with wildcard
     * support so e.g. an Owner role granted {@code *} passes everything).
     * Throws 403 (not 401) on denial so the dashboard shows "forbidden"
     * instead of logging the user out.
     */
    public @NotNull Principal requirePermission(@NotNull Context ctx, @NotNull String key) {
        Principal p = require(ctx);
        if (!can(p, key)) {
            throw new ForbiddenResponse("Missing permission: " + key);
        }
        return p;
    }

    public void requireInternal(@NotNull Context ctx) {
        String header = ctx.header("X-Internal-Secret");
        if (header == null || !header.equals(internalSecret)) {
            throw new ForbiddenResponse("Internal secret missing or invalid");
        }
    }

    /** Convenience: pulls a Session out of storage by token, ignoring API keys. */
    public @NotNull Session requireSession(@NotNull Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new UnauthorizedResponse("Bearer token missing");
        }
        String token = header.substring("Bearer ".length()).trim();
        return storage.findSession(token)
                .orElseThrow(() -> new UnauthorizedResponse("Session expired or invalid"));
    }
}
