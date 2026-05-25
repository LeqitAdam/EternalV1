package de.eternal.bungee.listener;

import de.eternal.bungee.EternalBungee;
import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.time.DurationParser;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Optional;

public final class BungeeChatListener implements Listener {

    private final EternalBungee plugin;

    public BungeeChatListener(@NotNull EternalBungee plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChat(@NotNull ChatEvent event) {
        if (event.isCommand() || event.isCancelled()) return;
        if (!(event.getSender() instanceof ProxiedPlayer player)) return;

        Optional<PunishmentEntry> mute = plugin.punishments().activeMute(player.getUniqueId());
        if (mute.isEmpty()) return;

        event.setCancelled(true);
        PunishmentEntry m = mute.get();
        String remaining = m.isPermanent() ? "permanent"
                : DurationParser.formatRemaining(
                        Math.max(0, m.expiresAt().getEpochSecond() - Instant.now().getEpochSecond()));
        plugin.messages().send(player, "mute-blocked-chat",
                "reason", m.reasonLabel(),
                "remaining", remaining);
    }
}
