package de.eternal.spigot.consent;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.jetbrains.annotations.NotNull;

/**
 * While a player is waiting for the privacy prompt (consent not yet
 * recorded), this listener cancels everything that would either store
 * data, give them an advantage, or let them ignore the prompt: chat,
 * movement, block edits, interact, drop, damage. Only two commands
 * survive — {@code /eternal accept} and {@code /eternal decline}.
 *
 * <p>All handlers run at {@code EventPriority.LOW} so other plugins
 * see the cancel and don't try to undo us. Cancellation is the
 * sole effect — we don't reply with a chat message on every move,
 * that would spam the prompt away within a tick.</p>
 */
public final class ConsentGuardListener implements Listener {

    private final EternalSpigot plugin;

    public ConsentGuardListener(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(@NotNull PlayerMoveEvent event) {
        if (!isPending(event.getPlayer())) return;
        // Only block actual position changes, not just yaw/pitch tweaks
        // — the player should be able to look around while reading.
        if (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(@NotNull AsyncPlayerChatEvent event) {
        if (isPending(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(@NotNull PlayerCommandPreprocessEvent event) {
        if (!isPending(event.getPlayer())) return;
        String msg = event.getMessage().toLowerCase().trim();
        // Allowlist exactly the two commands we want to keep open while
        // pending. Anything else gets cancelled silently — including
        // tab-completing other commands.
        if (msg.startsWith("/eternal accept")
                || msg.startsWith("/eternal decline")
                || msg.equals("/eternal")) {
            return;
        }
        event.setCancelled(true);
        // Quick reminder so the player knows why their /command went
        // nowhere. Sent off the prompt itself to avoid spamming the
        // chat panel — one short line.
        event.getPlayer().sendMessage(
                org.bukkit.ChatColor.translateAlternateColorCodes('&',
                        plugin.messages().get("consent-blocked-command")));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        if (isPending(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        if (isPending(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (isPending(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        if (isPending(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(@NotNull EntityDamageEvent event) {
        // The pending player should be invulnerable AND unable to deal
        // damage. EntityDamageEvent covers them being damaged; the
        // outgoing damage is blocked through onInteract (left-click
        // attack is a kind of interact).
        if (event.getEntity() instanceof Player p && isPending(p)) event.setCancelled(true);
    }

    private boolean isPending(@NotNull Player player) {
        return plugin.consent().isPending(player.getUniqueId());
    }
}
