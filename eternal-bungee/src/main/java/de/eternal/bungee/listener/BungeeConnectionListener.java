package de.eternal.bungee.listener;

import de.eternal.bungee.BungeeTiers;
import de.eternal.bungee.EternalBungee;
import de.eternal.core.integration.CloudPermsAccess;
import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.time.DurationParser;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class BungeeConnectionListener implements Listener {

    private final EternalBungee plugin;
    private final CloudPermsAccess cloudPerms;

    public BungeeConnectionListener(@NotNull EternalBungee plugin) {
        this.plugin = plugin;
        this.cloudPerms = new CloudPermsAccess(plugin.getLogger());
    }

    @EventHandler
    public void onLogin(@NotNull LoginEvent event) {
        var pending = event.getConnection();
        var uuid = pending.getUniqueId();
        if (uuid == null) return;

        event.registerIntent(plugin);
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            try {
                Optional<PunishmentEntry> ban = plugin.punishments().activeBan(uuid);
                if (ban.isPresent()) {
                    PunishmentEntry b = ban.get();
                    String dur = b.isPermanent() ? "permanent"
                            : DurationParser.formatRemaining(
                                    Math.max(0, b.expiresAt().getEpochSecond() - Instant.now().getEpochSecond()));
                    String screen = plugin.messages().format("ban-kick-screen",
                            "reason", b.reasonLabel(),
                            "duration", dur,
                            "id", b.id());
                    event.setCancelled(true);
                    event.setCancelReason(TextComponent.fromLegacyText(
                            ChatColor.translateAlternateColorCodes('&', screen)));
                }
            } finally {
                event.completeIntent(plugin);
            }
        });
    }

    @EventHandler
    public void onPostLogin(@NotNull PostLoginEvent event) {
        var p = event.getPlayer();
        String addr = p.getAddress() == null ? "?" : p.getAddress().getAddress().getHostAddress();
        int tier = BungeeTiers.of(p);

        List<String> groups = cloudPerms.groupsOf(p.getUniqueId());
        String group = groups.isEmpty() ? p.getName() : groups.get(0);

        ProxyServer.getInstance().getScheduler().runAsync(plugin, () ->
                plugin.storage().recordProfile(p.getUniqueId(), p.getName(), addr, tier, group));
    }

    @EventHandler
    public void onQuit(@NotNull PlayerDisconnectEvent event) {
        plugin.staff().logout(event.getPlayer().getUniqueId());
    }
}
