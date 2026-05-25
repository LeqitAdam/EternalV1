package de.eternal.core.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Small helpers to pull typed values out of the raw nested Maps that platform
 * YAML loaders produce. Keeps core decoupled from Bukkit/Bungee config APIs.
 */
public final class Configs {

    private Configs() {
    }

    public static @NotNull String stringOr(@NotNull Map<String, Object> map, @NotNull String key, @NotNull String fallback) {
        Object v = map.get(key);
        return v == null ? fallback : v.toString();
    }

    public static @Nullable String stringOrNull(@NotNull Map<String, Object> map, @NotNull String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    public static int intOr(@NotNull Map<String, Object> map, @NotNull String key, int fallback) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static long longOr(@NotNull Map<String, Object> map, @NotNull String key, long fallback) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v == null) return fallback;
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static boolean boolOr(@NotNull Map<String, Object> map, @NotNull String key, boolean fallback) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return fallback;
        return Boolean.parseBoolean(v.toString());
    }

    @SuppressWarnings("unchecked")
    public static @NotNull Map<String, Object> sectionOr(@NotNull Map<String, Object> map, @NotNull String key) {
        Object v = map.get(key);
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static @NotNull List<Map<String, Object>> sectionListOr(@NotNull Map<String, Object> map, @NotNull String key) {
        Object v = map.get(key);
        if (v instanceof List<?> l) return (List<Map<String, Object>>) l;
        return Collections.emptyList();
    }
}
