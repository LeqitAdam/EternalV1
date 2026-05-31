package de.eternal.api;

import de.eternal.core.config.ReasonsConfig;
import de.eternal.core.storage.EternalStorage;
import de.eternal.core.storage.sql.SqlStorage;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public final class Main {

    public static void main(String[] args) throws Exception {
        Path workdir = Paths.get(".").toAbsolutePath().normalize();
        Path cfgFile = workdir.resolve("api.yml");
        if (!Files.exists(cfgFile)) {
            try (InputStream in = Main.class.getResourceAsStream("/api.yml")) {
                if (in == null) throw new IOException("api.yml-Template fehlt im JAR");
                Files.copy(in, cfgFile);
            }
            System.out.println("[Eternal-API] api.yml angelegt — bitte API-Keys + DB-Zugang setzen und neu starten.");
            return;
        }

        Map<String, Object> raw = loadYaml(cfgFile);
        ApiConfig cfg = ApiConfig.fromMap(raw, workdir);

        if (cfg.apiKeys().isEmpty()) {
            System.err.println("[Eternal-API] Keine API-Keys konfiguriert — Login geht nur ueber den ingame-Link-Flow.");
        }
        if (cfg.internalSecret().equals("REPLACE_WITH_RANDOM_INTERNAL_SECRET")) {
            System.err.println("[Eternal-API] WARNING: internal.shared-secret nicht gesetzt — Plugin-Aufrufe werden abgewiesen.");
        }

        EternalStorage storage = new SqlStorage(cfg.database());
        storage.init();
        Runtime.getRuntime().addShutdownHook(new Thread(storage::close));

        // reasons.yml lives alongside api.yml in the working directory; copy
        // the bundled template on first run so admin-only ban detection works
        // without operators having to seed it manually.
        Path reasonsFile = workdir.resolve("reasons.yml");
        if (!Files.exists(reasonsFile)) {
            try (InputStream in = Main.class.getResourceAsStream("/reasons.yml")) {
                if (in != null) Files.copy(in, reasonsFile);
            }
        }
        ReasonsConfig reasonsCfg = ReasonsConfig.fromMap(
                Files.exists(reasonsFile) ? loadYaml(reasonsFile) : Map.of());

        // Permission engine. Registry seeds the hardcoded defaults +
        // reason-scoped keys (one per configured ban reason). SqlStorage
        // implements PermissionStorage, so the same connection pool
        // backs roles + grants.
        de.eternal.core.permission.PermissionRegistry permRegistry =
                new de.eternal.core.permission.PermissionRegistry();
        for (var r : reasonsCfg.all()) permRegistry.registerReasonScoped(r.id(), r.label());
        de.eternal.core.permission.PermissionService permService =
                new de.eternal.core.permission.PermissionService(
                        (de.eternal.core.permission.PermissionStorage) storage, storage, permRegistry);

        Auth auth = new Auth(cfg.apiKeys(), storage, cfg.internalSecret(), permService);
        Routes routes = new Routes(storage, auth, cfg, reasonsCfg);

        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.http.defaultContentType = "application/json";
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> {
                    if (cfg.server().allowedOrigins().contains("*")) rule.anyHost();
                    else cfg.server().allowedOrigins().forEach(rule::allowHost);
                });
            });
            config.jsonMapper(new JavalinGsonMapper());
        }).start(cfg.server().host(), cfg.server().port());

        app.exception(io.javalin.http.HttpResponseException.class, (e, ctx) -> {
            ctx.status(e.getStatus());
            ctx.json(Map.of("error", e.getMessage() == null ? "error" : e.getMessage()));
        });
        app.exception(Exception.class, (e, ctx) -> {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.json(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
            e.printStackTrace();
        });

        routes.register(app);

        System.out.println("[Eternal-API] hoert auf " + cfg.server().host() + ":" + cfg.server().port()
                + " (" + cfg.apiKeys().size() + " API-Keys, db=" + cfg.database().type() + ")");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path file) throws IOException {
        try (var reader = Files.newBufferedReader(file)) {
            Object o = new Yaml().load(reader);
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }
    }
}
