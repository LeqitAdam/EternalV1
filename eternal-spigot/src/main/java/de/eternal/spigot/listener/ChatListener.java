package de.eternal.spigot.listener;

import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.time.DurationParser;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Optional;

/**
 * Cancels chat messages from muted players. The number-in-chat selection flow
 * is gone — number now goes directly on the command line.
 */
public final class ChatListener implements Listener {

    private final EternalSpigot plugin;

    public ChatListener(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(@NotNull AsyncPlayerChatEvent event) {
        Optional<PunishmentEntry> mute = plugin.punishments().activeMute(event.getPlayer().getUniqueId());
        if (mute.isEmpty()) return;

        event.setCancelled(true);
        PunishmentEntry m = mute.get();
        String remaining = m.isPermanent()
                ? "permanent"
                : DurationParser.formatRemaining(
                        Math.max(0, m.expiresAt().getEpochSecond() - Instant.now().getEpochSecond()));
        Bukkit.getScheduler().runTask(plugin, () ->
                plugin.messages().send(event.getPlayer(), "mute-blocked-chat",
                        "reason", m.reasonLabel(),
                        "remaining", remaining));
    }
}
