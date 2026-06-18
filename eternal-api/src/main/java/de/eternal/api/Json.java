package de.eternal.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

public final class Json {

    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (src, type, ctx) ->
                    src == null ? null : new com.google.gson.JsonPrimitive(src.toEpochMilli()))
            // Inverse of the serializer above — lets us read epoch-millis back
            // into Instant when parsing persisted snapshots (e.g. the report
            // chat-history JSON in Routes#reportChat). Without this, fromJson
            // on any type carrying an Instant would fail at runtime.
            .registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>) (json, type, ctx) ->
                    json == null || json.isJsonNull() ? null
                            : Instant.ofEpochMilli(json.getAsLong()))
            .disableHtmlEscaping()
            .create();

    private Json() {
    }

    public static @NotNull String stringify(@NotNull Object obj) {
        return GSON.toJson(obj);
    }
}
