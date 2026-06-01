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
                    // appealBlock = entire "Hinweis: …" line (incl. blank
                    // line before it) OR empty string when no note exists.
                    // Keeps the kick-screen tidy when no shorten/decision
                    // attached a player-facing note.
                    String note = b.lastAppealMessage();
                    String appealBlock = (note == null || note.isBlank())
                            ? ""
                            : "\n\n  &dHinweis&8: &7" + note;
                    String screen = plugin.messages().format("ban-kick-screen",
                            "reason", b.reasonLabel(),
                            "duration", dur,
                            "id", b.id(),
                            "appealBlock", appealBlock);
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

        // GDPR gate — only persist the profile row once the player has
        // accepted the privacy policy on the Spigot side. Before that
        // we deliberately store nothing. On the next reconnect (after
        // accept), this branch fires and we backfill the profile.
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            if (!plugin.storage().hasConsent(p.getUniqueId())) return;
            plugin.storage().recordProfile(p.getUniqueId(), p.getName(), addr, tier, group);
        });
    }

    @EventHandler
    public void onQuit(@NotNull PlayerDisconnectEvent event) {
        plugin.staff().logout(event.getPlayer().getUniqueId());
    }
}
