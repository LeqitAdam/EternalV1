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
import java.util.UUID;

/** /sethome, /home, /delhome, /homes. */
public final class HomeCommand implements CommandExecutor, TabCompleter {

    private final EternalSpigot plugin;

    public HomeCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Player p = Cmd.player(plugin, sender);
        if (p == null) return true;
        UUID u = p.getUniqueId();
        String server = plugin.serverName();
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "sethome" -> {
                String name = args.length >= 1 ? args[0] : plugin.defaultHomeName();
                Loc loc = Locations.toLoc(p.getLocation());
                boolean unlimited = p.hasPermission("eternal.base.home.unlimited");
                Cmd.async(plugin, () -> {
                    boolean exists = plugin.base().getHome(u, server, name).isPresent();
                    int count = plugin.base().homeCount(u, server);
                    if (!exists && !unlimited && count >= plugin.maxHomes()) {
                        Cmd.sync(plugin, () -> plugin.messages().send(p, "home-limit", "max", plugin.maxHomes()));
                        return;
                    }
                    plugin.base().setHome(u, server, name, loc);
                    Cmd.sync(plugin, () -> plugin.messages().send(p, "home-set", "name", name));
                });
            }
            case "home" -> {
                String name = args.length >= 1 ? args[0] : plugin.defaultHomeName();
                Cmd.async(plugin, () -> {
                    Optional<Loc> h = plugin.base().getHome(u, server, name);
                    List<String> homes = h.isPresent() ? List.of() : plugin.base().listHomes(u, server);
                    Cmd.sync(plugin, () -> {
                        if (h.isPresent()) {
                            Location loc = Locations.toLocation(h.get());
                            if (loc == null) {
                                plugin.messages().send(p, "world-missing", "world", h.get().world());
                                return;
                            }
                            plugin.teleport(p, loc);
                            plugin.messages().send(p, "home-tp", "name", name);
                        } else if (homes.isEmpty()) {
                            plugin.messages().send(p, "home-none-any");
                        } else {
                            plugin.messages().send(p, "home-none", "name", name);
                        }
                    });
                });
            }
            case "delhome" -> {
                if (args.length < 1) {
                    plugin.messages().send(p, "usage", "usage", "/delhome <name>");
                    return true;
                }
                String name = args[0];
                Cmd.async(plugin, () -> {
                    boolean ok = plugin.base().deleteHome(u, server, name);
                    Cmd.sync(plugin, () -> plugin.messages().send(p, ok ? "home-deleted" : "home-none", "name", name));
                });
            }
            case "homes" -> Cmd.async(plugin, () -> {
                List<String> homes = plugin.base().listHomes(u, server);
                Cmd.sync(plugin, () -> {
                    if (homes.isEmpty()) plugin.messages().send(p, "homes-empty");
                    else plugin.messages().send(p, "homes-list", "homes", String.join(", ", homes));
                });
            });
            default -> { }
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        // Home names are in the DB; avoid a sync query on tab — return nothing.
        return List.of();
    }
}
