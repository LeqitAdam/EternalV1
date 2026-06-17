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

public final class PartyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "accept", "deny", "invite", "leave", "disband", "kick", "list", "menu");

    private final EternalPartySpigot plugin;

    public PartyCommand(@NotNull EternalPartySpigot plugin) {
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
            plugin.service().openMenu(p);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept" -> {
                if (args.length >= 2) {
                    try {
                        plugin.service().acceptInvite(p, Long.parseLong(args[1]));
                    } catch (NumberFormatException ex) {
                        plugin.service().acceptNewestInvite(p);
                    }
                } else {
                    plugin.service().acceptNewestInvite(p);
                }
            }
            case "deny", "decline" -> plugin.service().denyNewestInvite(p);
            case "invite", "add" -> {
                if (args.length >= 2) plugin.service().inviteByName(p, args[1]);
                else plugin.messages().send(p, "party-usage");
            }
            case "leave" -> plugin.service().leave(p);
            case "disband" -> plugin.service().disband(p);
            case "kick" -> {
                if (args.length >= 2) plugin.service().kick(p, args[1]);
                else plugin.messages().send(p, "party-usage");
            }
            case "list", "info" -> plugin.service().list(p);
            case "menu" -> plugin.service().openMenu(p);
            default -> plugin.messages().send(p, "party-usage");
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
        if (args.length == 2 && (args[0].equalsIgnoreCase("invite") || args[0].equalsIgnoreCase("kick"))) {
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
