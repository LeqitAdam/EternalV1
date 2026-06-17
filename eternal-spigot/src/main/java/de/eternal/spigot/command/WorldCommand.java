package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/** /time, /day, /night, /weather (operate on the player's world). */
public final class WorldCommand implements CommandExecutor, TabCompleter {

    private final EternalSpigot plugin;

    public WorldCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Player p = Cmd.player(plugin, sender);
        if (p == null) return true;
        World world = p.getWorld();
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "day" -> setTime(p, world, 1000, "day");
            case "night" -> setTime(p, world, 13000, "night");
            case "time" -> time(p, world, args);
            case "weather" -> weather(p, world, args);
            default -> { }
        }
        return true;
    }

    private void time(Player p, World world, String[] args) {
        if (args.length < 1) {
            plugin.messages().send(p, "usage", "usage", "/time <day|night|set <ticks>>");
            return;
        }
        String a = args[0].toLowerCase(Locale.ROOT);
        if (a.equals("day")) { setTime(p, world, 1000, "day"); return; }
        if (a.equals("night")) { setTime(p, world, 13000, "night"); return; }
        String raw = a.equals("set") && args.length >= 2 ? args[1] : a;
        try {
            setTime(p, world, Long.parseLong(raw), raw);
        } catch (NumberFormatException ex) {
            plugin.messages().send(p, "invalid-number");
        }
    }

    private void setTime(Player p, World world, long ticks, String label) {
        world.setTime(ticks);
        plugin.messages().send(p, "time-set", "time", label);
    }

    private void weather(Player p, World world, String[] args) {
        if (args.length < 1) {
            plugin.messages().send(p, "weather-invalid");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "clear", "sun" -> { world.setStorm(false); world.setThundering(false); }
            case "rain", "storm" -> { world.setStorm(true); world.setThundering(false); }
            case "thunder" -> { world.setStorm(true); world.setThundering(true); }
            default -> {
                plugin.messages().send(p, "weather-invalid");
                return;
            }
        }
        plugin.messages().send(p, "weather-set", "weather", args[0].toLowerCase(Locale.ROOT));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        String n = command.getName().toLowerCase(Locale.ROOT);
        if (n.equals("time") && args.length == 1) return List.of("day", "night", "set");
        if (n.equals("weather") && args.length == 1) return List.of("clear", "rain", "thunder");
        return List.of();
    }
}
