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

/** /gamemode + shortcuts /gmc /gms /gma /gmsp. */
public final class GamemodeCommand implements CommandExecutor, TabCompleter {

    private final EternalSpigot plugin;

    public GamemodeCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        GameMode mode;
        String playerArg;
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "gmc" -> { mode = GameMode.CREATIVE; playerArg = arg(args, 0); }
            case "gms" -> { mode = GameMode.SURVIVAL; playerArg = arg(args, 0); }
            case "gma" -> { mode = GameMode.ADVENTURE; playerArg = arg(args, 0); }
            case "gmsp" -> { mode = GameMode.SPECTATOR; playerArg = arg(args, 0); }
            default -> {
                if (args.length < 1) {
                    plugin.messages().send(sender, "usage", "usage", "/gamemode <0-3> [player]");
                    return true;
                }
                GameMode parsed = parse(args[0]);
                if (parsed == null) {
                    plugin.messages().send(sender, "gamemode-invalid");
                    return true;
                }
                mode = parsed;
                playerArg = arg(args, 1);
            }
        }
        apply(sender, mode, playerArg);
        return true;
    }

    private void apply(CommandSender sender, GameMode mode, @Nullable String playerArg) {
        Player target;
        if (playerArg != null) {
            if (!Cmd.has(sender, "eternal.base.gamemode.others")) {
                plugin.messages().send(sender, "no-permission");
                return;
            }
            target = Cmd.online(plugin, sender, playerArg);
            if (target == null) return;
        } else {
            target = Cmd.player(plugin, sender);
            if (target == null) return;
        }
        target.setGameMode(mode);
        String pretty = mode.name().toLowerCase(Locale.ROOT);
        if (target.equals(sender)) {
            plugin.messages().send(sender, "gamemode-self", "mode", pretty);
        } else {
            plugin.messages().send(sender, "gamemode-other", "player", target.getName(), "mode", pretty);
            plugin.messages().send(target, "gamemode-self", "mode", pretty);
        }
    }

    private static @Nullable String arg(String[] args, int i) {
        return args.length > i ? args[i] : null;
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
        boolean isGamemode = command.getName().equalsIgnoreCase("gamemode");
        if (isGamemode && args.length == 1) {
            return new ArrayList<>(List.of("survival", "creative", "adventure", "spectator"));
        }
        int playerIdx = isGamemode ? 2 : 1;
        if (args.length == playerIdx) {
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
