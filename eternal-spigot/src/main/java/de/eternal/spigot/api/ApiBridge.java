package de.eternal.spigot.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Thin HTTP client for the Eternal REST API. Uses the JDK HttpClient so we
 * don't drag in extra dependencies. All calls go out on the calling thread —
 * the caller is responsible for running them async.
 */
public final class ApiBridge {

    public record PendingAction(long id, @NotNull String type, @NotNull String payload) {
    }

    public record Config(boolean enabled, @NotNull String baseUrl, @NotNull String sharedSecret,
                         int pollIntervalSeconds) {
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

    public @NotNull Config config() {
        return config;
    }

    /** Confirms an ingame /eternal link <code> against the API. */
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

    /** Polls the API for pending actions targeted at a staff member. */
    public @NotNull List<PendingAction> pendingActions(@NotNull UUID uuid) {
        if (!enabled()) return List.of();
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(config.baseUrl() + "/actions/pending?uuid=" + uuid))
                            .header("X-Internal-Secret", config.sharedSecret())
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return List.of();
            JsonElement parsed = JsonParser.parseString(resp.body());
            if (!parsed.isJsonArray()) return List.of();
            JsonArray arr = parsed.getAsJsonArray();
            List<PendingAction> out = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                out.add(new PendingAction(
                        obj.get("id").getAsLong(),
                        obj.get("type").getAsString(),
                        obj.get("payload").getAsString()));
            }
            return out;
        } catch (Exception ex) {
            logger.warning("API pendingActions failed: " + ex.getMessage());
            return List.of();
        }
    }

    public void consumeAction(long id) {
        if (!enabled()) return;
        try {
            http.send(HttpRequest.newBuilder(URI.create(config.baseUrl() + "/actions/" + id + "/consumed"))
                            .header("X-Internal-Secret", config.sharedSecret())
                            .timeout(Duration.ofSeconds(5))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception ex) {
            logger.warning("API consumeAction failed: " + ex.getMessage());
        }
    }
}
