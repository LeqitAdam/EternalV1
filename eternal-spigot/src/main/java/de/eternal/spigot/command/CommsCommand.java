package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
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
import java.util.UUID;

/** /broadcast, /msg, /reply, /kill. */
public final class CommsCommand implements CommandExecutor, TabCompleter {

    private final EternalSpigot plugin;

    public CommsCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "broadcast" -> broadcast(sender, args);
            case "msg" -> msg(sender, args);
            case "reply" -> reply(sender, args);
            case "kill" -> kill(sender, args);
            default -> { }
        }
        return true;
    }

    private void broadcast(CommandSender sender, String[] args) {
        if (args.length < 1) {
            plugin.messages().send(sender, "usage", "usage", "/broadcast <text>");
            return;
        }
        Bukkit.broadcastMessage(plugin.messages().format("broadcast-format", "message", String.join(" ", args)));
    }

    private void msg(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "usage", "usage", "/msg <player> <text>");
            return;
        }
        Player target = Cmd.online(plugin, sender, args[0]);
        if (target == null) return;
        if (sender instanceof Player sp && sp.equals(target)) {
            plugin.messages().send(sender, "msg-self");
            return;
        }
        String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        deliver(sender, target, text);
    }

    private void reply(CommandSender sender, String[] args) {
        Player p = Cmd.player(plugin, sender);
        if (p == null) return;
        if (args.length < 1) {
            plugin.messages().send(p, "usage", "usage", "/reply <text>");
            return;
        }
        UUID targetId = plugin.sessions().reply.get(p.getUniqueId());
        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
        if (target == null) {
            plugin.messages().send(p, "reply-none");
            return;
        }
        deliver(p, target, String.join(" ", args));
    }

    /** Sends the private message both ways and records reply targets. */
    private void deliver(CommandSender sender, Player target, String text) {
        String senderName = sender instanceof Player sp ? sp.getName() : "Console";
        sender.sendMessage(plugin.messages().format("msg-out", "target", target.getName(), "message", text));
        target.sendMessage(plugin.messages().format("msg-in", "sender", senderName, "message", text));
        if (sender instanceof Player sp) {
            plugin.sessions().reply.put(sp.getUniqueId(), target.getUniqueId());
        }
        plugin.sessions().reply.put(target.getUniqueId(),
                sender instanceof Player sp ? sp.getUniqueId() : target.getUniqueId());
    }

    private void kill(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 1) {
            if (!Cmd.has(sender, "eternal.base.kill.others")) {
                plugin.messages().send(sender, "no-permission");
                return;
            }
            target = Cmd.online(plugin, sender, args[0]);
            if (target == null) return;
        } else {
            target = Cmd.player(plugin, sender);
            if (target == null) return;
        }
        target.setHealth(0.0);
        if (target.equals(sender)) {
            plugin.messages().send(sender, "kill-self");
        } else {
            plugin.messages().send(sender, "kill-other", "player", target.getName());
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        String n = command.getName().toLowerCase(Locale.ROOT);
        if ((n.equals("msg") || n.equals("kill")) && args.length == 1) {
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
