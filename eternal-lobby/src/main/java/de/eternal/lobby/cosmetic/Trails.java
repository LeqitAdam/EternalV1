package de.eternal.lobby.cosmetic;

import de.eternal.lobby.EternalLobby;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cosmetic particle trails ("boots") — a footstep particle spawned behind a
 * moving player. The chosen trail id is stored in the player's PDC so it
 * survives relogs. Particle names are resolved version-tolerantly (the enum was
 * renamed in 1.20.5), so the same jar runs 1.19 → latest.
 */
public final class Trails {

    public record Trail(@NotNull String id, @NotNull String name, @NotNull Material icon, @NotNull Particle particle) {
    }

    public static final String NONE = "none";

    private final EternalLobby plugin;
    private final NamespacedKey choiceKey;
    private final List<Trail> trails = new ArrayList<>();
    private final Map<UUID, Location> lastLoc = new HashMap<>();
    private BukkitTask task;

    public Trails(@NotNull EternalLobby plugin) {
        this.plugin = plugin;
        this.choiceKey = new NamespacedKey(plugin, "lobby_trail");
        add("flame", "&6Flammen", Material.BLAZE_POWDER, "FLAME");
        add("heart", "&dHerzen", Material.RED_DYE, "HEART");
        add("note", "&aNoten", Material.NOTE_BLOCK, "NOTE");
        add("cloud", "&fWolken", Material.WHITE_WOOL, "CLOUD");
        add("crit", "&cCrit", Material.IRON_SWORD, "CRIT");
        add("lava", "&4Lava", Material.LAVA_BUCKET, "LAVA", "DRIP_LAVA");
        add("spark", "&eFunken", Material.GLOWSTONE_DUST, "HAPPY_VILLAGER", "VILLAGER_HAPPY");
        add("portal", "&5Portal", Material.ENDER_PEARL, "PORTAL");
        add("snow", "&bSchnee", Material.SNOWBALL, "SNOWFLAKE", "ITEM_SNOWBALL", "SNOW_SHOVEL");
    }

    /** Starts the per-tick trail emitter (every 4 ticks). */
    public void start() {
        this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 4L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    public @NotNull List<Trail> all() {
        return trails;
    }

    public @Nullable Trail byId(@NotNull String id) {
        for (Trail t : trails) if (t.id().equalsIgnoreCase(id)) return t;
        return null;
    }

    /** The player's chosen trail id ({@link #NONE} when none / unset). */
    public @NotNull String choice(@NotNull Player p) {
        String v = p.getPersistentDataContainer().get(choiceKey, PersistentDataType.STRING);
        return v == null ? NONE : v;
    }

    public void setChoice(@NotNull Player p, @NotNull String id) {
        p.getPersistentDataContainer().set(choiceKey, PersistentDataType.STRING, id);
    }

    private void tick() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            String id = choice(p);
            if (id.equals(NONE)) {
                lastLoc.remove(p.getUniqueId());
                continue;
            }
            Trail t = byId(id);
            if (t == null) continue;
            Location loc = p.getLocation();
            Location prev = lastLoc.put(p.getUniqueId(), loc.clone());
            // Only emit while actually moving — a standing player has no trail.
            if (prev == null || !prev.getWorld().equals(loc.getWorld())
                    || prev.distanceSquared(loc) < 0.02) continue;
            loc.getWorld().spawnParticle(t.particle(), loc.getX(), loc.getY() + 0.1, loc.getZ(),
                    4, 0.2, 0.05, 0.2, 0.0);
        }
    }

    private void add(@NotNull String id, @NotNull String name, @NotNull Material icon, @NotNull String... particleNames) {
        Particle particle = resolve(particleNames);
        if (particle != null) trails.add(new Trail(id, name, icon, particle));
    }

    /** First matching {@link Particle} constant — names differ across versions. */
    private static @Nullable Particle resolve(@NotNull String... names) {
        for (String n : names) {
            try {
                return Particle.valueOf(n);
            } catch (IllegalArgumentException ignored) {
                // try next candidate name
            }
        }
        return null;
    }
}
