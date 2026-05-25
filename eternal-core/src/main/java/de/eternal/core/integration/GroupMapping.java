package de.eternal.core.integration;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps CloudNet group names to Eternal permission nodes + a numeric tier.
 * When a player is in multiple matching groups, the highest tier wins and
 * permissions are unioned.
 */
public final class GroupMapping {

    public record Entry(int tier, @NotNull Set<String> permissions) {
    }

    private final Map<String, Entry> entries;

    public GroupMapping(@NotNull Map<String, Entry> entries) {
        Map<String, Entry> normalized = new LinkedHashMap<>();
        for (var e : entries.entrySet()) normalized.put(e.getKey().toLowerCase(), e.getValue());
        this.entries = Collections.unmodifiableMap(normalized);
    }

    public @NotNull Resolution resolve(@NotNull List<String> playerGroups) {
        int tier = 0;
        Set<String> perms = new LinkedHashSet<>();
        Entry def = entries.get("default");
        if (def != null) {
            tier = Math.max(tier, def.tier());
            perms.addAll(def.permissions());
        }
        for (String g : playerGroups) {
            Entry e = entries.get(g.toLowerCase());
            if (e == null) continue;
            tier = Math.max(tier, e.tier());
            perms.addAll(e.permissions());
        }
        return new Resolution(tier, perms);
    }

    public record Resolution(int tier, @NotNull Set<String> permissions) {
    }
}
