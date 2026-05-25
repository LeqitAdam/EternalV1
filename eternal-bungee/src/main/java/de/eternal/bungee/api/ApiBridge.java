package de.eternal.bungee.api;

import com.google.gson.Gson;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Bungee-side mirror of the Spigot {@code ApiBridge}. Currently only used by
 * {@code /eternal link <code>} to bestaetigen the dashboard account link via
 * the REST-API.
 */
public final class ApiBridge {

    public record Config(boolean enabled, @NotNull String baseUrl, @NotNull String sharedSecret) {
    }

    private static final Gson GSON = new Gson();

    private final HttpClient http;
    private final Config config;
    private final Logger logger;

    public ApiBridge(@NotNull Config config, @NotNull Logger logger) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.config = config;
        this.logger = logger;
    }

    public boolean enabled() {
        return config.enabled() && !config.baseUrl().isBlank() && !config.sharedSecret().isBlank();
    }

    public boolean confirmLink(@NotNull String code, @NotNull UUID uuid, @NotNull String name) {
        if (!enabled()) return false;
        String body = GSON.toJson(Map.of("code", code, "uuid", uuid.toString(), "name", name));
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(config.baseUrl() + "/auth/link/confirm"))
                            .header("Content-Type", "application/json")
                            .header("X-Internal-Secret", config.sharedSecret())
                            .timeout(Duration.ofSeconds(5))
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception ex) {
            logger.warning("API confirmLink failed: " + ex.getMessage());
            return false;
        }
    }
}
