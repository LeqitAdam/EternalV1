package de.eternal.replay.internal.playback;

import de.eternal.replay.api.ReplayApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.function.Function;

/**
 * Wires hotbar clicks during playback into {@link PlaybackSession}
 * methods. Also stops playback automatically when the viewer
 * disconnects.
 */
public final class PlaybackListener implements Listener {

    private final ReplayApi api;
    private final Function<Player, PlaybackSession> sessionLookup;

    public PlaybackListener(@NotNull ReplayApi api,
                            @NotNull Function<Player, PlaybackSession> sessionLookup) {
        this.api = api;
        this.sessionLookup = sessionLookup;
    }

    @EventHandler
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (!api.isViewing(event.getPlayer().getUniqueId())) return;
        ItemStack hand = event.getItem();
        if (hand == null) return;
        HotbarControls.Control ctrl = HotbarControls.read(hand);
        if (ctrl == null) return;
        event.setCancelled(true);
        PlaybackSession s = sessionLookup.apply(event.getPlayer());
        if (s == null) return;
        switch (ctrl) {
            case PLAY_PAUSE  -> s.togglePause();
            case SEEK_BACK   -> s.seek(-10_000);
            case SEEK_FWD    -> s.seek(+10_000);
            case SPEED_DOWN  -> s.changeSpeed(-0.25);
            case SPEED_UP    -> s.changeSpeed(+0.25);
            case EXIT        -> api.stopPlayback(event.getPlayer().getUniqueId());
            case OPEN_INVENTORY -> s.openNearestInventorySnapshot();
            case INFO -> {
                var h = s.replay();
                if (h == null) return;
                Player p = event.getPlayer();
                p.sendMessage("§d» §7Replay §f#" + h.id());
                p.sendMessage("§d» §7Quelle§8: §f" + h.kind() + (h.sourceId() == null ? "" : (" §8#§f" + h.sourceId())));
                p.sendMessage("§d» §7Ziel§8: §e" + h.primaryPlayerName());
                p.sendMessage("§d» §7Dauer§8: §f" + h.durationSeconds() + "s");
            }
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        if (api.isViewing(event.getPlayer().getUniqueId())) {
            api.stopPlayback(event.getPlayer().getUniqueId());
        }
    }

    /* -----------------------------------------------------------------
     * World-interaction-Sperren für Viewer im Replay-Modus. Adventure
     * fängt Block-Place/Break ab, aber Drop, Damage, Inventar-Klick
     * brauchen extra-cancel. Wir cancellen everything am
     * Replay-Viewer, das nicht expressiv erlaubt ist.
     * ----------------------------------------------------------------- */

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBreak(@NotNull BlockBreakEvent event) {
        if (api.isViewing(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlace(@NotNull BlockPlaceEvent event) {
        if (api.isViewing(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        if (api.isViewing(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventory(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (!api.isViewing(p.getUniqueId())) return;
        // Inventar-Snapshots werden in einer eigenen "InventoryHolder"-
        // GUI angezeigt; die ist read-only. Replay-Hotbar im eigenen
        // Inventar wird durch PlaybackListener.onInteract verarbeitet,
        // hier sperren wir einfach jegliche Bewegung von Items.
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (api.isViewing(p.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDamage(@NotNull EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && api.isViewing(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
