package de.eternal.spigot;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory runtime state for the base system: last positions (/back), god +
 * vanish toggles, /reply targets, and pending /tpa requests with expiry.
 * Nothing here needs to survive a restart.
 */
public final class Sessions {

    public final Map<UUID, Location> back = new ConcurrentHashMap<>();
    public final Set<UUID> god = ConcurrentHashMap.newKeySet();
    public final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    public final Map<UUID, UUID> reply = new ConcurrentHashMap<>();

    private final Map<UUID, Tpa> tpa = new ConcurrentHashMap<>();

    /** A pending teleport request keyed by the TARGET (who must accept).
     *  {@code here == true} means /tpahere (target is pulled to the sender). */
    public record Tpa(@NotNull UUID from, boolean here, long expiresAt) {
    }

    public void addTpa(@NotNull UUID target, @NotNull UUID from, boolean here, int expirySeconds) {
        tpa.put(target, new Tpa(from, here, System.currentTimeMillis() + expirySeconds * 1000L));
    }

    /** Latest non-expired request for {@code target}, or null. */
    public @Nullable Tpa getTpa(@NotNull UUID target) {
        Tpa t = tpa.get(target);
        if (t == null) return null;
        if (t.expiresAt() < System.currentTimeMillis()) {
            tpa.remove(target);
            return null;
        }
        return t;
    }

    public void removeTpa(@NotNull UUID target) {
        tpa.remove(target);
    }

    public void clear(@NotNull UUID uuid) {
        back.remove(uuid);
        god.remove(uuid);
        vanished.remove(uuid);
        reply.remove(uuid);
        tpa.remove(uuid);
    }
}
