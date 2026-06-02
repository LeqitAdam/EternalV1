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
        // GDPR gate: if the player hasn't accepted the privacy policy
        // yet, we DON'T write profile / session / display-name rows.
        // The ConsentGuardListener freezes them and shows the prompt;
        // when they /eternal accept, runPostConsent() below executes
        // the same path we'd normally run on join.
        if (!plugin.consent().hasConsent(p.getUniqueId())) {
            plugin.consent().markPending(p.getUniqueId());
            // Open the consent GUI in a delayed task so the player's
            // own client + any chat-plugin formatters have settled.
            // The GUI is the primary path; clicking inside it never
            // leaves the spigot, so Bungee can't intercept it.
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> plugin.consentGui().open(p), 20L);
            return;
        }
        runPostConsent(p);
    }

    /** The original onJoin body — only runs once we know the player
     *  has accepted the privacy policy (either earlier, persisted in
     *  eternal_consent, or just now via {@code /eternal accept}). */
    public void runPostConsent(@NotNull org.bukkit.entity.Player p) {
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

    /** Sends the multi-line privacy-policy prompt with clickable
     *  /eternal accept and /eternal decline buttons. Uses the
     *  translation file for the body so admins can customise the
     *  wording without touching code. */
    private void sendConsentPrompt(@NotNull org.bukkit.entity.Player p) {
        // The translation key holds the whole prompt as a multi-line
        // block. We send it as one message — Minecraft handles \n.
        String body = plugin.messages().format("consent-prompt", "player", p.getName());
        for (String line : body.split("\n")) {
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
        // Clickable buttons below the body. We can't append click-events
        // to multi-line legacy messages cleanly, so the buttons are
        // their own chat line at the bottom.
        net.md_5.bungee.api.chat.TextComponent accept = new net.md_5.bungee.api.chat.TextComponent(
                ChatColor.translateAlternateColorCodes('&', "&a[Akzeptieren]"));
        accept.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/eternal accept"));
        accept.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                new net.md_5.bungee.api.chat.hover.content.Text(
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                                ChatColor.translateAlternateColorCodes('&',
                                        "&7Datenschutzbestimmungen akzeptieren und spielen.")))));

        net.md_5.bungee.api.chat.TextComponent space = new net.md_5.bungee.api.chat.TextComponent("  ");

        net.md_5.bungee.api.chat.TextComponent decline = new net.md_5.bungee.api.chat.TextComponent(
                ChatColor.translateAlternateColorCodes('&', "&c[Ablehnen]"));
        decline.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/eternal decline"));
        decline.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                new net.md_5.bungee.api.chat.hover.content.Text(
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
                                ChatColor.translateAlternateColorCodes('&',
                                        "&7Ablehnen kickt dich und löscht alle Daten, die wir bereits über dich haben.")))));

        p.spigot().sendMessage(accept, space, decline);
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        var uuid = event.getPlayer().getUniqueId();
        plugin.staff().logout(uuid);
        // If the player was still waiting on the consent prompt and
        // just disconnected (alt+F4 or kick from a different listener),
        // drop the pending flag so a reconnect re-prompts cleanly.
        plugin.consent().clearPending(uuid);
        Long sessionId = activeSessions.remove(uuid);
        if (sessionId != null) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                    plugin.storage().endSession(sessionId));
        }
    }
}
