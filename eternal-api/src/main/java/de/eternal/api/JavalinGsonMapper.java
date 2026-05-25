package de.eternal.api;

import io.javalin.json.JsonMapper;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;

public final class JavalinGsonMapper implements JsonMapper {

    @Override
    public @NotNull String toJsonString(@NotNull Object obj, @NotNull Type type) {
        return Json.GSON.toJson(obj, type);
    }

    @Override
    public <T> @NotNull T fromJsonString(@NotNull String json, @NotNull Type targetType) {
        return Json.GSON.fromJson(json, targetType);
    }

    @Override
    public <T> @NotNull T fromJsonStream(@NotNull InputStream json, @NotNull Type targetType) {
        return Json.GSON.fromJson(new InputStreamReader(json), targetType);
    }
}
