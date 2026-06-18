package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import de.eternal.spigot.Locations;
import de.eternal.core.model.Loc;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** /setwarp, /warp, /delwarp, /warps. */
public final class WarpCommand implements CommandExecutor, TabCompleter {

    private final EternalSpigot plugin;

    public WarpCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String server = plugin.serverName();
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "setwarp" -> {
                Player p = Cmd.player(plugin, sender);
                if (p == null) return true;
                if (args.length < 1) {
                    plugin.messages().send(p, "usage", "usage", "/setwarp <name>");
                    return true;
                }
                String name = args[0];
                Loc loc = Locations.toLoc(p.getLocation());
                Cmd.async(plugin, () -> {
                    plugin.base().setWarp(server, name, loc);
                    Cmd.sync(plugin, () -> plugin.messages().send(p, "warp-set", "name", name));
                });
            }
            case "warp" -> {
                Player p = Cmd.player(plugin, sender);
                if (p == null) return true;
                if (args.length < 1) {
                    plugin.messages().send(p, "usage", "usage", "/warp <name>");
                    return true;
                }
                String name = args[0];
                Cmd.async(plugin, () -> {
                    Optional<Loc> w = plugin.base().getWarp(server, name);
                    Cmd.sync(plugin, () -> {
                        if (w.isEmpty()) {
                            plugin.messages().send(p, "warp-none", "name", name);
                            return;
                        }
                        Location loc = Locations.toLocation(w.get());
                        if (loc == null) {
                            plugin.messages().send(p, "world-missing", "world", w.get().world());
                            return;
                        }
                        plugin.teleport(p, loc);
                        plugin.messages().send(p, "warp-tp", "name", name);
                    });
                });
            }
            case "delwarp" -> {
                if (args.length < 1) {
                    plugin.messages().send(sender, "usage", "usage", "/delwarp <name>");
                    return true;
                }
                String name = args[0];
                Cmd.async(plugin, () -> {
                    boolean ok = plugin.base().deleteWarp(server, name);
                    Cmd.sync(plugin, () -> plugin.messages().send(sender, ok ? "warp-deleted" : "warp-none", "name", name));
                });
            }
            case "warps" -> Cmd.async(plugin, () -> {
                List<String> warps = plugin.base().listWarps(server);
                Cmd.sync(plugin, () -> {
                    if (warps.isEmpty()) plugin.messages().send(sender, "warps-empty");
                    else plugin.messages().send(sender, "warps-list", "warps", String.join(", ", warps));
                });
            });
            default -> { }
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        return List.of();
    }
}
