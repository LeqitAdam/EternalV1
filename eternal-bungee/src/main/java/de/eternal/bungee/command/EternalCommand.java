package de.eternal.bungee.command;

import de.eternal.bungee.EternalBungee;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public final class EternalCommand extends Command {

    private final EternalBungee plugin;

    public EternalCommand(@NotNull EternalBungee plugin) {
        // No top-level permission — /eternal link soll fuer jeden Spieler
        // gehen. /eternal reload pruefen wir intern auf eternal.admin.
        super("eternal");
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 1) {
            plugin.messages().send(sender, "usage-eternal");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(sender);
            case "link" -> handleLink(sender, args);
            // accept/decline live on the Spigot side as a GUI now —
            // Bungee can't open inventories. Send the player a tiny
            // hint instead of the generic "unknown action" message
            // so they don't think the system's broken.
            case "accept", "decline" -> plugin.messages().send(sender, "consent-use-gui");
            default -> plugin.messages().send(sender, "unknown-action", "action", args[0]);
        }
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
            sender.sendMessage(net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                    "§cReload fehlgeschlagen: " + ex.getMessage()));
        }
    }

    private void handleLink(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof ProxiedPlayer p)) {
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
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            boolean ok = plugin.apiBridge().confirmLink(code, p.getUniqueId(), p.getName());
            plugin.messages().send(sender, ok ? "link-success" : "link-failed");
        });
    }
}
