package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One central tab-completer for all Eternal commands. Suggests online player
 * names (the FAKE name for nicked players, so staff tab what they see and it
 * still resolves) for player-targeting commands, and fixed sub-command lists
 * for the rest. Registered on each command in {@code EternalSpigot}.
 */
public final class EternalTabCompleter implements TabCompleter {

    private final EternalSpigot plugin;

    public EternalTabCompleter(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    /** Commands whose FIRST arg is a player name. */
    private static final java.util.Set<String> PLAYER_ARG0 = java.util.Set.of(
            "ban", "unban", "mute", "unmute", "report", "lookup", "history", "resethistory",
            "playerinfo", "msg", "tp", "tphere", "tpa", "tpahere", "invsee", "kill", "vanish");
    /** Commands whose SECOND arg is a player name (first arg = value/mode). */
    private static final java.util.Set<String> PLAYER_ARG1 = java.util.Set.of(
            "gamemode", "gm", "fly", "god", "heal", "feed", "clearinventory", "ci",
            "enderchest", "ec", "tppos");
    /** Fixed sub-command options for arg 0. */
    private static final Map<String, List<String>> SUBS = Map.ofEntries(
            Map.entry("gamemode", List.of("0", "1", "2", "3", "survival", "creative", "adventure", "spectator")),
            Map.entry("gm", List.of("0", "1", "2", "3")),
            Map.entry("time", List.of("day", "night", "noon", "midnight", "set")),
            Map.entry("weather", List.of("clear", "rain", "thunder")),
            Map.entry("eternal", List.of("reload", "link")),
            Map.entry("socialspy", List.of("on", "off")),
            Map.entry("speed", List.of("walk", "fly")),
            Map.entry("modify", List.of("setduration", "setreason"))
    );

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);

        if (args.length == 1) {
            if (SUBS.containsKey(cmd)) return filter(SUBS.get(cmd), prefix);
            if (PLAYER_ARG0.contains(cmd)) return players(prefix);
        }
        if (args.length == 2 && PLAYER_ARG1.contains(cmd)) {
            return players(prefix);
        }
        // speed: /speed <walk|fly> <amount> <player>
        if (cmd.equals("speed") && args.length == 3) return players(prefix);
        // gamemode: /gm <mode> <player>
        if ((cmd.equals("gamemode") || cmd.equals("gm")) && args.length == 2) return players(prefix);
        return List.of();
    }

    /** Online player names — the nick name for disguised players. */
    private @NotNull List<String> players(@NotNull String prefix) {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            String nick = plugin.nickService().nickNameOf(p.getUniqueId());
            String name = nick != null ? nick : p.getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(name);
        }
        return out;
    }

    private static @NotNull List<String> filter(@NotNull List<String> options, @NotNull String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(o);
        return out;
    }
}
