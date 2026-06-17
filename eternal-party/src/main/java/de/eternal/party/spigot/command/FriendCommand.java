package de.eternal.party.spigot.command;

import de.eternal.party.spigot.EternalPartySpigot;
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

public final class FriendCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("add", "accept", "deny", "remove", "list", "menu");

    private final EternalPartySpigot plugin;

    public FriendCommand(@NotNull EternalPartySpigot plugin) {
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
            plugin.service().friendList(p);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add", "invite", "request" -> {
                if (args.length >= 2) plugin.service().addFriendByName(p, args[1]);
                else plugin.messages().send(p, "friend-usage");
            }
            case "accept" -> {
                if (args.length >= 2) plugin.service().acceptFriendRequest(p, args[1]);
                else plugin.messages().send(p, "friend-usage");
            }
            case "deny", "decline" -> {
                if (args.length >= 2) plugin.service().denyFriendRequest(p, args[1]);
                else plugin.messages().send(p, "friend-usage");
            }
            case "remove", "delete" -> {
                if (args.length >= 2) plugin.service().removeFriendByName(p, args[1]);
                else plugin.messages().send(p, "friend-usage");
            }
            case "list" -> plugin.service().friendList(p);
            case "menu", "gui" -> plugin.service().openFriends(p);
            default -> plugin.messages().send(p, "friend-usage");
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            List<String> out = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(online.getName());
                }
            }
            return out;
        }
        return List.of();
    }
}
