package de.eternal.autonicker.command;

import de.eternal.autonicker.EternalAutonicker;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class AutonickCommand implements CommandExecutor, TabCompleter {

    private final EternalAutonicker plugin;

    public AutonickCommand(@NotNull EternalAutonicker plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.messages().format("players-only"));
            return true;
        }
        if (args.length == 0) {
            plugin.nickService().toggle(p);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on", "enable" -> plugin.nickService().setNick(p, true);
            case "off", "disable" -> plugin.nickService().setNick(p, false);
            default -> plugin.nickService().toggle(p);
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> out = new java.util.ArrayList<>();
            for (String s : List.of("on", "off")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        return List.of();
    }
}
