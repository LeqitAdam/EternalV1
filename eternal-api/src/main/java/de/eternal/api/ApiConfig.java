package de.eternal.api;

import de.eternal.core.config.Configs;
import de.eternal.core.config.DatabaseConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApiConfig {

    /**
     * A trusted static API key. Legacy ADMIN/MOD roles are gone — a static key
     * grants FULL access (it's a server-config secret), so it has no role field.
     * Authorization for everything else is permission-based via the user's
     * CloudNet role grants.
     */
    public record ApiKey(@NotNull String key, @NotNull String name, @Nullable UUID uuid) {
    }

    public record Server(@NotNull String host, int port, @NotNull List<String> allowedOrigins) {
    }

    public record SessionConfig(int ttlSeconds, int linkTtlSeconds) {
    }

    private final Server server;
    private final DatabaseConfig database;
    private final List<ApiKey> apiKeys;
    private final String internalSecret;
    private final SessionConfig session;

    public ApiConfig(@NotNull Server server, @NotNull DatabaseConfig database,
                     @NotNull List<ApiKey> keys, @NotNull String internalSecret,
                     @NotNull SessionConfig session) {
        this.server = server;
        this.database = database;
        this.apiKeys = List.copyOf(keys);
        this.internalSecret = internalSecret;
        this.session = session;
    }

    public Server server() { return server; }
    public DatabaseConfig database() { return database; }
    public List<ApiKey> apiKeys() { return apiKeys; }
    public String internalSecret() { return internalSecret; }
    public SessionConfig session() { return session; }

    public static @NotNull ApiConfig fromMap(@NotNull Map<String, Object> raw, @NotNull Path workingDir) {
        Map<String, Object> serverRaw = Configs.sectionOr(raw, "server");
        Map<String, Object> corsRaw = Configs.sectionOr(serverRaw, "cors");
        List<String> origins = new ArrayList<>();
        for (Object o : Configs.sectionListOr(corsRaw, "allowed-origins")) origins.add(String.valueOf(o));
        Server server = new Server(
                Configs.stringOr(serverRaw, "host", "0.0.0.0"),
                Configs.intOr(serverRaw, "port", 7070),
                origins
        );

        DatabaseConfig db = DatabaseConfig.fromMap(Configs.sectionOr(raw, "database"), workingDir);

        List<ApiKey> keys = new ArrayList<>();
        for (Map<String, Object> entry : Configs.sectionListOr(raw, "api-keys")) {
            String k = Configs.stringOr(entry, "key", "");
            if (k.isBlank() || k.equals("REPLACE_WITH_RANDOM_STRING")) continue;
            // `role:` in the entry (if any) is ignored now — static keys are full-access.
            String name = Configs.stringOr(entry, "name", "api-key");
            String uuidRaw = Configs.stringOrNull(entry, "uuid");
            UUID uuid = uuidRaw == null ? null : UUID.fromString(uuidRaw);
            keys.add(new ApiKey(k, name, uuid));
        }

        String secret = Configs.stringOr(Configs.sectionOr(raw, "internal"),
                "shared-secret", "REPLACE_WITH_RANDOM_INTERNAL_SECRET");

        Map<String, Object> sessionRaw = Configs.sectionOr(raw, "session");
        SessionConfig session = new SessionConfig(
                Configs.intOr(sessionRaw, "ttl-seconds", 86400),
                Configs.intOr(sessionRaw, "link-ttl-seconds", 300)
        );

        return new ApiConfig(server, db, keys, secret, session);
    }
}
