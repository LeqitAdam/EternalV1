package de.eternal.replay.internal.playback;

import de.eternal.replay.api.ReplayApi;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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
}
