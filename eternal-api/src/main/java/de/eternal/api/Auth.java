package de.eternal.api;

import de.eternal.core.model.Session;
import de.eternal.core.storage.EternalStorage;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UnauthorizedResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bearer-token authentication. Accepts either:
 *  - A static API key from the api.yml file (role = ADMIN | MOD), or
 *  - A web session token issued via the /auth/link flow (stored in DB).
 *
 * The "internal" shared secret is checked separately for plugin->API calls
 * via the X-Internal-Secret header.
 */
public final class Auth {

    /** Logical principal — combines API-key entries and DB-backed sessions. */
    public record Principal(@NotNull String name, @NotNull String role, @Nullable UUID uuid) {
        public boolean isAdmin() { return role.equalsIgnoreCase("ADMIN"); }
        public boolean isStaff() { return isAdmin() || role.equalsIgnoreCase("MOD"); }
    }

    private final Map<String, ApiConfig.ApiKey> byKey = new HashMap<>();
    private final EternalStorage storage;
    private final String internalSecret;

    public Auth(@NotNull List<ApiConfig.ApiKey> keys,
                @NotNull EternalStorage storage,
                @NotNull String internalSecret) {
        for (var k : keys) byKey.put(k.key(), k);
        this.storage = storage;
        this.internalSecret = internalSecret;
    }

    public @Nullable Principal resolve(@NotNull Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        String token = header.substring("Bearer ".length()).trim();

        ApiConfig.ApiKey staticKey = byKey.get(token);
        if (staticKey != null) {
            return new Principal(staticKey.name(), staticKey.role().name(), staticKey.uuid());
        }
        return storage.findSession(token)
                .map(s -> new Principal(s.userName(), s.role(), s.userUuid()))
                .orElse(null);
    }

    public @NotNull Principal require(@NotNull Context ctx) {
        Principal p = resolve(ctx);
        if (p == null) throw new UnauthorizedResponse("Bearer token missing or invalid");
        return p;
    }

    public @NotNull Principal requireAdmin(@NotNull Context ctx) {
        Principal p = require(ctx);
        if (!p.isAdmin()) {
            ctx.status(HttpStatus.FORBIDDEN);
            throw new UnauthorizedResponse("Admin role required");
        }
        return p;
    }

    public @NotNull Principal requireStaff(@NotNull Context ctx) {
        Principal p = require(ctx);
        if (!p.isStaff()) {
            ctx.status(HttpStatus.FORBIDDEN);
            throw new UnauthorizedResponse("Staff role required");
        }
        return p;
    }

    public void requireInternal(@NotNull Context ctx) {
        String header = ctx.header("X-Internal-Secret");
        if (header == null || !header.equals(internalSecret)) {
            ctx.status(HttpStatus.FORBIDDEN);
            throw new UnauthorizedResponse("Internal secret missing or invalid");
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
