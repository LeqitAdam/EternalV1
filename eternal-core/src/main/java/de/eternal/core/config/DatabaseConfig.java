package de.eternal.core.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Map;

public record DatabaseConfig(
        @NotNull Type type,
        @Nullable Path embeddedFile,
        @Nullable String host,
        int port,
        @Nullable String database,
        @Nullable String username,
        @Nullable String password,
        int poolSize
) {

    public enum Type {
        /** Embedded file DB — H2 in MySQL-compat mode (replaces the old SQLite,
         *  pure Java, much smaller jar). */
        H2,
        MYSQL
    }

    public static @NotNull DatabaseConfig fromMap(@NotNull Map<String, Object> raw, @NotNull Path dataFolder) {
        String typeRaw = Configs.stringOr(raw, "type", "h2").toUpperCase();
        // Back-compat: old configs say "sqlite" — the embedded DB is H2 now.
        Type type = (typeRaw.equals("MYSQL")) ? Type.MYSQL : Type.H2;

        return switch (type) {
            case H2 -> new DatabaseConfig(
                    Type.H2,
                    // H2 appends ".mv.db"; strip a trailing ".db" so an old
                    // "eternal.db" config yields "eternal.mv.db", not "eternal.db.mv.db".
                    dataFolder.resolve(Configs.stringOr(raw, "file", "eternal").replaceFirst("\\.db$", "")),
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
