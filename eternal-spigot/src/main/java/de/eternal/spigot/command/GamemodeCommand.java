package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.GameMode;
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

/** /gamemode (alias /gm) — used as {@code /gm <0-3> [Spieler]}, e.g. /gm 1 Notch. */
public final class GamemodeCommand implements CommandExecutor, TabCompleter {

    private final EternalSpigot plugin;

    public GamemodeCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length < 1) {
            plugin.messages().send(sender, "usage", "usage", "/gm <0-3> [Spieler]");
            return true;
        }
        GameMode mode = parse(args[0]);
        if (mode == null) {
            plugin.messages().send(sender, "gamemode-invalid");
            return true;
        }
        String playerArg = args.length > 1 ? args[1] : null;

        Player target;
        if (playerArg != null) {
            if (!Cmd.has(sender, "eternal.base.gamemode.others")) {
                plugin.messages().send(sender, "no-permission");
                return true;
            }
            target = Cmd.online(plugin, sender, playerArg);
            if (target == null) return true;
        } else {
            target = Cmd.player(plugin, sender);
            if (target == null) return true;
        }
        target.setGameMode(mode);
        String pretty = mode.name().toLowerCase(Locale.ROOT);
        if (target.equals(sender)) {
            plugin.messages().send(sender, "gamemode-self", "mode", pretty);
        } else {
            plugin.messages().send(sender, "gamemode-other", "player", target.getName(), "mode", pretty);
            plugin.messages().send(target, "gamemode-self", "mode", pretty);
        }
        return true;
    }

    private static @Nullable GameMode parse(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "0", "s", "survival" -> GameMode.SURVIVAL;
            case "1", "c", "creative" -> GameMode.CREATIVE;
            case "2", "a", "adventure" -> GameMode.ADVENTURE;
            case "3", "sp", "spectator" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return new ArrayList<>(List.of("0", "1", "2", "3"));
        }
        if (args.length == 2) {
            String pre = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Player pl : plugin.getServer().getOnlinePlayers()) {
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(pl.getName());
            }
            return out;
        }
        return List.of();
    }
}
