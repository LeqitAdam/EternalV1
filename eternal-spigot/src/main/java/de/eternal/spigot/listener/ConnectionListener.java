package de.eternal.spigot.listener;

import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.time.DurationParser;
import de.eternal.spigot.EternalSpigot;
import de.eternal.spigot.Tiers;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class ConnectionListener implements Listener {

    private final EternalSpigot plugin;
    private final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> activeSessions =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ConnectionListener(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(@NotNull AsyncPlayerPreLoginEvent event) {
        Optional<PunishmentEntry> ban = plugin.punishments().activeBan(event.getUniqueId());
        if (ban.isEmpty()) return;

        PunishmentEntry b = ban.get();
        String duration = b.isPermanent()
                ? "permanent"
                : DurationParser.formatRemaining(
                        Math.max(0, b.expiresAt().getEpochSecond() - Instant.now().getEpochSecond()));

        // appealBlock is the WHOLE "Hinweis: …" line (incl. leading
        // newlines and the prefix) — or empty when no note exists. This
        // way the kick-screen template stays placeholder-only, and the
        // line disappears cleanly instead of leaving "Hinweis: " dangling.
        String note = b.lastAppealMessage();
        String appealBlock = (note == null || note.isBlank())
                ? ""
                : "\n\n  &dHinweis&8: &7" + note;
        String screen = plugin.messages().format("ban-kick-screen",
                "reason", b.reasonLabel(),
                "duration", duration,
                "id", b.id(),
                "appealBlock", appealBlock);
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                ChatColor.translateAlternateColorCodes('&', screen));
    }

    // MONITOR ensures chat-plugins like CloudNet-Chat / SimpleNameTags have
    // already mutated player.getDisplayName() by the time we capture it.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        var p = event.getPlayer();
        String addr = p.getAddress() == null ? "?" : p.getAddress().getAddress().getHostAddress();
        int tier = Tiers.of(p);

        // Try CloudNet first; fall back to the player's display name so the
        // lookup output still has something meaningful to show on a server
        // without CloudPerms.
        List<String> groups = plugin.cloudPerms().groupsOf(p.getUniqueId());
        String group = groups.isEmpty()
                ? ChatColor.stripColor(p.getDisplayName())
                : groups.get(0);
        // DisplayName as produced by CloudNet-Chat / nametag plugins. We
        // capture it twice: once now (MONITOR, in case the chat plugin used
        // LOW/NORMAL priority and already finished), and again 2 seconds
        // later (in case the chat plugin defers its work to an async task or
        // a later tick). The second capture overrides the first via the
        // COALESCE-style upsert in SqlStorage — non-empty wins.
        String displayName = p.getDisplayName();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.storage().recordProfile(p.getUniqueId(), p.getName(), addr, tier, group, displayName);
            long sessionId = plugin.storage().startSession(p.getUniqueId(), p.getName(), addr);
            // Re-capture display 40 ticks (~2s) later so deferred chat-plugin
            // formatters can finish before we lock in the cached value.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                String later = p.getDisplayName();
                List<String> g2 = plugin.cloudPerms().groupsOf(p.getUniqueId());
                String group2 = g2.isEmpty() ? ChatColor.stripColor(later) : g2.get(0);
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                        plugin.storage().recordProfile(p.getUniqueId(), p.getName(), addr,
                                Tiers.of(p), group2, later));
            }, 40L);
            activeSessions.put(p.getUniqueId(), sessionId);
        });
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        plugin.staff().logout(event.getPlayer().getUniqueId());
        Long sessionId = activeSessions.remove(event.getPlayer().getUniqueId());
        if (sessionId != null) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                    plugin.storage().endSession(sessionId));
        }
    }
}
