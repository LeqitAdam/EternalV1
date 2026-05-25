package de.eternal.spigot.command;

import de.eternal.core.storage.EternalStorage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a player name to (UUID, name). Prefers online player; falls back to
 * the stored profile so we can ban/lookup players who are offline but have
 * been seen before.
 */
public final class TargetResolver {

    public record Target(@NotNull UUID uuid, @NotNull String name, boolean online) {
    }

    private final EternalStorage storage;

    public TargetResolver(@NotNull EternalStorage storage) {
        this.storage = storage;
    }

    public @NotNull Optional<Target> resolve(@NotNull String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return Optional.of(new Target(online.getUniqueId(), online.getName(), true));
        return storage.findProfileByName(name)
                .map(p -> new Target(p.uuid(), p.name(), false));
    }

    public @Nullable Player onlineFor(@NotNull Target target) {
        return Bukkit.getPlayer(target.uuid());
    }
}
