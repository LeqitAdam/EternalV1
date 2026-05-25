package de.eternal.core.integration;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pulls a {@link GroupMapping} out of the platform-agnostic Map produced by
 * Bukkit/Bungee YAML loaders. Same parse logic both sides.
 */
public final class MappingLoader {

    private MappingLoader() {
    }

    @SuppressWarnings("unchecked")
    public static @NotNull GroupMapping fromMap(@NotNull Map<String, Object> raw) {
        Map<String, Object> groups = raw.get("groups") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : new LinkedHashMap<>();

        Map<String, GroupMapping.Entry> out = new LinkedHashMap<>();
        for (var entry : groups.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> body)) continue;
            int tier = body.get("tier") instanceof Number n ? n.intValue() : 0;
            Set<String> perms = new LinkedHashSet<>();
            Object permsRaw = body.get("permissions");
            if (permsRaw instanceof List<?> list) {
                for (Object o : list) perms.add(String.valueOf(o));
            }
            out.put(entry.getKey(), new GroupMapping.Entry(tier, perms));
        }
        return new GroupMapping(out);
    }
}
