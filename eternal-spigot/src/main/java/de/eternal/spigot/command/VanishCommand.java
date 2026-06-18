package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
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

/** /vanish [player] — hide a player from everyone else. */
public final class VanishCommand implements CommandExecutor, TabCompleter {

    private final EternalSpigot plugin;

    public VanishCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Player target;
        if (args.length >= 1) {
            target = Cmd.online(plugin, sender, args[0]);
            if (target == null) return true;
        } else {
            target = Cmd.player(plugin, sender);
            if (target == null) return true;
        }
        boolean enable = !plugin.sessions().vanished.contains(target.getUniqueId());
        if (enable) plugin.sessions().vanished.add(target.getUniqueId());
        else plugin.sessions().vanished.remove(target.getUniqueId());

        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other.equals(target)) continue;
            if (enable) other.hidePlayer(plugin, target);
            else other.showPlayer(plugin, target);
        }
        String state = plugin.messages().format(enable ? "state-on" : "state-off");
        if (target.equals(sender)) {
            plugin.messages().send(sender, "vanish-self", "state", state);
        } else {
            plugin.messages().send(sender, "vanish-other", "player", target.getName(), "state", state);
            plugin.messages().send(target, "vanish-self", "state", state);
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Player pl : plugin.getServer().getOnlinePlayers()) {
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(pl.getName());
            }
            return out;
        }
        return List.of();
    }
}
