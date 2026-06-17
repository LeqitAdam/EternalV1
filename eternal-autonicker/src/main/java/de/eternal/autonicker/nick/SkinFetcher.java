package de.eternal.autonicker.nick;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches a player's skin texture (value + signature) from the Mojang API by
 * name. Blocking — always call from an async thread. Results are cached for the
 * server lifetime; failures return null and the nick degrades to name-only.
 */
public final class SkinFetcher {

    private static final String NAME_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String SESSION_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";

    private final ConcurrentHashMap<String, Skin> cache = new ConcurrentHashMap<>();

    public record Skin(@NotNull String value, @Nullable String signature) {
    }

    public @Nullable Skin fetch(@NotNull String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Skin cached = cache.get(key);
        if (cached != null) return cached;
        try {
            String uuid = fetchUuid(name);
            if (uuid == null) return null;
            Skin skin = fetchSkin(uuid);
            if (skin != null) cache.put(key, skin);
            return skin;
        } catch (Exception ex) {
            return null;
        }
    }

    private @Nullable String fetchUuid(@NotNull String name) throws Exception {
        JsonObject obj = getJson(NAME_URL + name);
        if (obj == null || !obj.has("id")) return null;
        return obj.get("id").getAsString();
    }

    private @Nullable Skin fetchSkin(@NotNull String uuid) throws Exception {
        JsonObject obj = getJson(SESSION_URL + uuid + "?unsigned=false");
        if (obj == null || !obj.has("properties")) return null;
        JsonArray props = obj.getAsJsonArray("properties");
        for (int i = 0; i < props.size(); i++) {
            JsonObject p = props.get(i).getAsJsonObject();
            if (p.has("name") && "textures".equals(p.get("name").getAsString())) {
                String value = p.get("value").getAsString();
                String signature = p.has("signature") ? p.get("signature").getAsString() : null;
                return new Skin(value, signature);
            }
        }
        return null;
    }

    private @Nullable JsonObject getJson(@NotNull String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("User-Agent", "EternalAutonicker");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            return null;
        }
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } finally {
            conn.disconnect();
        }
    }
}
