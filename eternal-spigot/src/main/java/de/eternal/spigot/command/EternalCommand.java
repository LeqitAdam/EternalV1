package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public final class EternalCommand implements CommandExecutor {

    private final EternalSpigot plugin;

    public EternalCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length < 1) {
            plugin.messages().send(sender, "usage-eternal");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(sender);
            case "link" -> handleLink(sender, args);
            default -> plugin.messages().send(sender, "unknown-action", "action", args[0]);
        }
        return true;
    }

    private void handleReload(@NotNull CommandSender sender) {
        if (!sender.hasPermission("eternal.admin")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        try {
            plugin.reloadEverything();
            plugin.messages().send(sender, "config-reloaded");
        } catch (Exception ex) {
            sender.sendMessage(org.bukkit.ChatColor.RED + "Reload fehlgeschlagen: " + ex.getMessage());
            plugin.getLogger().warning("Reload failed: " + ex.getMessage());
        }
    }

    private void handleLink(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "usage-eternal");
            return;
        }
        if (!plugin.apiBridge().enabled()) {
            plugin.messages().send(sender, "link-api-disabled");
            return;
        }
        String code = args[1].trim().toUpperCase(Locale.ROOT);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = plugin.apiBridge().confirmLink(code, p.getUniqueId(), p.getName());
            Bukkit.getScheduler().runTask(plugin, () ->
                    plugin.messages().send(sender, ok ? "link-success" : "link-failed"));
        });
    }
}
