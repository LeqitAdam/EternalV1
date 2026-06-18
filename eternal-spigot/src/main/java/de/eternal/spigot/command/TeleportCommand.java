package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /tp, /tphere, /tppos, /top. */
public final class TeleportCommand implements CommandExecutor, TabCompleter {

    private final EternalSpigot plugin;

    public TeleportCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "tp" -> tp(sender, args);
            case "tphere" -> tphere(sender, args);
            case "tppos" -> tppos(sender, args);
            case "top" -> top(sender);
            default -> { }
        }
        return true;
    }

    private void tp(CommandSender sender, String[] args) {
        if (args.length == 1) {
            Player p = Cmd.player(plugin, sender);
            if (p == null) return;
            Player target = Cmd.online(plugin, sender, args[0]);
            if (target == null) return;
            plugin.teleport(p, target.getLocation());
            plugin.messages().send(p, "tp-self", "target", target.getName());
        } else if (args.length >= 2) {
            Player a = Cmd.online(plugin, sender, args[0]);
            Player b = Cmd.online(plugin, sender, args[1]);
            if (a == null || b == null) return;
            plugin.teleport(a, b.getLocation());
            plugin.messages().send(sender, "tp-other", "player", a.getName(), "target", b.getName());
        } else {
            plugin.messages().send(sender, "usage", "usage", "/tp <player> [target]");
        }
    }

    private void tphere(CommandSender sender, String[] args) {
        Player p = Cmd.player(plugin, sender);
        if (p == null) return;
        if (args.length < 1) {
            plugin.messages().send(p, "usage", "usage", "/tphere <player>");
            return;
        }
        Player target = Cmd.online(plugin, sender, args[0]);
        if (target == null) return;
        plugin.teleport(target, p.getLocation());
        plugin.messages().send(p, "tphere", "player", target.getName());
    }

    private void tppos(CommandSender sender, String[] args) {
        Player p = Cmd.player(plugin, sender);
        if (p == null) return;
        if (args.length < 3) {
            plugin.messages().send(p, "usage", "usage", "/tppos <x> <y> <z> [world]");
            return;
        }
        Double x = parse(args[0]);
        Double y = parse(args[1]);
        Double z = parse(args[2]);
        if (x == null || y == null || z == null) {
            plugin.messages().send(p, "invalid-number");
            return;
        }
        World world = args.length >= 4 ? plugin.getServer().getWorld(args[3]) : p.getWorld();
        if (world == null) {
            plugin.messages().send(p, "world-missing", "world", args[3]);
            return;
        }
        plugin.teleport(p, new Location(world, x, y, z, p.getLocation().getYaw(), p.getLocation().getPitch()));
        plugin.messages().send(p, "tppos", "x", fmt(x), "y", fmt(y), "z", fmt(z));
    }

    private void top(CommandSender sender) {
        Player p = Cmd.player(plugin, sender);
        if (p == null) return;
        Location loc = p.getLocation();
        int highest = p.getWorld().getHighestBlockYAt(loc);
        plugin.teleport(p, new Location(p.getWorld(), loc.getX(), highest + 1, loc.getZ(), loc.getYaw(), loc.getPitch()));
        plugin.messages().send(p, "top-done");
    }

    private static @Nullable Double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String fmt(double d) {
        return String.valueOf(Math.round(d));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if ((name.equals("tp") || name.equals("tphere")) && args.length <= 2) {
            String pre = args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Player pl : plugin.getServer().getOnlinePlayers()) {
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(pl.getName());
            }
            return out;
        }
        return List.of();
    }
}
