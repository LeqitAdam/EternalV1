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
            case "accept" -> handleAccept(sender);
            case "decline" -> handleDecline(sender);
            default -> plugin.messages().send(sender, "unknown-action", "action", args[0]);
        }
        return true;
    }

    /** GDPR accept path. Called from the clickable button in the join
     *  prompt OR by the player typing {@code /eternal accept}. No-op
     *  for non-pending players so spam-running it costs us nothing. */
    private void handleAccept(@NotNull CommandSender sender) {
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        if (!plugin.consent().isPending(p.getUniqueId())) {
            plugin.messages().send(sender, "consent-not-pending");
            return;
        }
        plugin.consent().accept(p);
        plugin.messages().send(sender, "consent-accepted");
    }

    /** GDPR decline path. Triggers the purge + the kick — both run
     *  inside {@code consent.decline}, the player won't see anything
     *  beyond the kick screen. */
    private void handleDecline(@NotNull CommandSender sender) {
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        if (!plugin.consent().isPending(p.getUniqueId())) {
            plugin.messages().send(sender, "consent-not-pending");
            return;
        }
        String kick = plugin.messages().format("consent-declined-kick");
        plugin.consent().decline(p, kick);
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
