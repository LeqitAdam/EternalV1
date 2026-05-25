package de.eternal.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSerializer;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

public final class Json {

    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (src, type, ctx) ->
                    src == null ? null : new com.google.gson.JsonPrimitive(src.toEpochMilli()))
            .disableHtmlEscaping()
            .create();

    private Json() {
    }

    public static @NotNull String stringify(@NotNull Object obj) {
        return GSON.toJson(obj);
    }
}
