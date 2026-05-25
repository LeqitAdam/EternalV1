package de.eternal.core.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Map;

public record DatabaseConfig(
        @NotNull Type type,
        @Nullable Path sqliteFile,
        @Nullable String host,
        int port,
        @Nullable String database,
        @Nullable String username,
        @Nullable String password,
        int poolSize
) {

    public enum Type {
        SQLITE,
        MYSQL
    }

    public static @NotNull DatabaseConfig fromMap(@NotNull Map<String, Object> raw, @NotNull Path dataFolder) {
        String typeRaw = Configs.stringOr(raw, "type", "sqlite").toUpperCase();
        Type type = Type.valueOf(typeRaw);

        return switch (type) {
            case SQLITE -> new DatabaseConfig(
                    Type.SQLITE,
                    dataFolder.resolve(Configs.stringOr(raw, "file", "eternal.db")),
                    null, 0, null, null, null,
                    Configs.intOr(raw, "pool-size", 4)
            );
            case MYSQL -> new DatabaseConfig(
                    Type.MYSQL,
                    null,
                    Configs.stringOr(raw, "host", "127.0.0.1"),
                    Configs.intOr(raw, "port", 3306),
                    Configs.stringOr(raw, "database", "eternal"),
                    Configs.stringOr(raw, "username", "eternal"),
                    Configs.stringOr(raw, "password", ""),
                    Configs.intOr(raw, "pool-size", 10)
            );
        };
    }
}
