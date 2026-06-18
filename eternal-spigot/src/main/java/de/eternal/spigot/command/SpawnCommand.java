package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import de.eternal.spigot.Locations;
import de.eternal.core.model.Loc;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;

/** /setspawn, /spawn, /back. */
public final class SpawnCommand implements CommandExecutor {

    private final EternalSpigot plugin;

    public SpawnCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Player p = Cmd.player(plugin, sender);
        if (p == null) return true;
        String server = plugin.serverName();
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "setspawn" -> {
                Loc loc = Locations.toLoc(p.getLocation());
                Cmd.async(plugin, () -> {
                    plugin.base().setSpawn(server, loc);
                    Cmd.sync(plugin, () -> plugin.messages().send(p, "spawn-set"));
                });
            }
            case "spawn" -> Cmd.async(plugin, () -> {
                Optional<Loc> s = plugin.base().getSpawn(server);
                Cmd.sync(plugin, () -> {
                    if (s.isEmpty()) {
                        plugin.messages().send(p, "spawn-none");
                        return;
                    }
                    Location loc = Locations.toLocation(s.get());
                    if (loc == null) {
                        plugin.messages().send(p, "world-missing", "world", s.get().world());
                        return;
                    }
                    plugin.teleport(p, loc);
                    plugin.messages().send(p, "spawn-tp");
                });
            });
            case "back" -> {
                Location last = plugin.sessions().back.get(p.getUniqueId());
                if (last == null) {
                    plugin.messages().send(p, "back-none");
                    return true;
                }
                plugin.teleport(p, last); // records the current spot, so /back toggles
                plugin.messages().send(p, "back-done");
            }
            default -> { }
        }
        return true;
    }
}
