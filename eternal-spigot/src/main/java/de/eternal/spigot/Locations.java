package de.eternal.spigot;

import de.eternal.core.model.Loc;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Converts between the storage {@link Loc} record and Bukkit {@link Location}. */
public final class Locations {

    private Locations() {
    }

    public static @NotNull Loc toLoc(@NotNull Location l) {
        String world = l.getWorld() == null ? "world" : l.getWorld().getName();
        return new Loc(world, l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    /** Bukkit location, or null when the stored world is not loaded. */
    public static @Nullable Location toLocation(@NotNull Loc l) {
        World w = Bukkit.getWorld(l.world());
        if (w == null) return null;
        return new Location(w, l.x(), l.y(), l.z(), l.yaw(), l.pitch());
    }
}
