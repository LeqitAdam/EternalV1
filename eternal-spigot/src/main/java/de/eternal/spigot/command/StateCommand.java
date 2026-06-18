package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
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

/** /god, /heal, /feed. */
public final class StateCommand implements CommandExecutor, TabCompleter {

    private final EternalSpigot plugin;

    public StateCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        Player target = resolve(sender, args, "eternal.base." + cmd + ".others");
        if (target == null) return true;
        switch (cmd) {
            case "god" -> god(sender, target);
            case "heal" -> heal(sender, target);
            case "feed" -> feed(sender, target);
            default -> { }
        }
        return true;
    }

    private @Nullable Player resolve(CommandSender sender, String[] args, String othersPerm) {
        if (args.length >= 1) {
            if (!Cmd.has(sender, othersPerm)) {
                plugin.messages().send(sender, "no-permission");
                return null;
            }
            return Cmd.online(plugin, sender, args[0]);
        }
        return Cmd.player(plugin, sender);
    }

    private void god(CommandSender sender, Player target) {
        boolean enable = !plugin.sessions().god.contains(target.getUniqueId());
        if (enable) plugin.sessions().god.add(target.getUniqueId());
        else plugin.sessions().god.remove(target.getUniqueId());
        String state = plugin.messages().format(enable ? "state-on" : "state-off");
        if (target.equals(sender)) {
            plugin.messages().send(sender, "god-self", "state", state);
        } else {
            plugin.messages().send(sender, "god-other", "player", target.getName(), "state", state);
            plugin.messages().send(target, "god-self", "state", state);
        }
    }

    private void heal(CommandSender sender, Player target) {
        AttributeInstance attr = target.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double max = attr != null ? attr.getValue() : 20.0;
        target.setHealth(max);
        target.setFireTicks(0);
        notify(sender, target, "heal-self", "heal-other");
    }

    private void feed(CommandSender sender, Player target) {
        target.setFoodLevel(20);
        target.setSaturation(20f);
        notify(sender, target, "feed-self", "feed-other");
    }

    private void notify(CommandSender sender, Player target, String selfKey, String otherKey) {
        if (target.equals(sender)) {
            plugin.messages().send(sender, selfKey);
        } else {
            plugin.messages().send(sender, otherKey, "player", target.getName());
            plugin.messages().send(target, selfKey);
        }
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
