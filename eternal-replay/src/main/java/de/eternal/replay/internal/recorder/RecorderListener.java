package de.eternal.replay.internal.recorder;

import de.eternal.replay.model.BlockEvent;
import de.eternal.replay.model.ChatEvent;
import de.eternal.replay.model.HitEvent;
import de.eternal.replay.model.ItemUseEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Maps Bukkit events into recordable buffer entries. Runs at MONITOR
 * priority + ignoreCancelled = true so we only log things that actually
 * happened — gives clean replay data even when other plugins cancel
 * actions for unrelated reasons.
 */
public final class RecorderListener implements Listener {

    private final ContinuousRecorder recorder;

    public RecorderListener(@NotNull ContinuousRecorder recorder) {
        this.recorder = recorder;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(@NotNull AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        var buf = recorder.bufferOf(p.getUniqueId(), p.getName());
        buf.push(new ChatEvent(buf.currentRelativeMs(), 0, event.getMessage()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(@NotNull BlockPlaceEvent event) {
        Player p = event.getPlayer();
        var buf = recorder.bufferOf(p.getUniqueId(), p.getName());
        buf.push(new BlockEvent(buf.currentRelativeMs(), 0, true,
                event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ(),
                event.getBlock().getBlockData().getAsString()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(@NotNull BlockBreakEvent event) {
        Player p = event.getPlayer();
        var buf = recorder.bufferOf(p.getUniqueId(), p.getName());
        buf.push(new BlockEvent(buf.currentRelativeMs(), 0, false,
                event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ(),
                event.getBlock().getBlockData().getAsString()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(@NotNull EntityDamageByEntityEvent event) {
        // Only record when at least the damager is a player — the victim
        // matters for context but we attach the hit to the damager's
        // buffer (that's whose actions we want to replay).
        if (!(event.getDamager() instanceof Player damager)) return;
        var buf = recorder.bufferOf(damager.getUniqueId(), damager.getName());
        double reach = damager.getLocation().distance(event.getEntity().getLocation());
        buf.push(new HitEvent(buf.currentRelativeMs(), 0,
                0, // victim idx — recorded per-buffer; multi-player wiring at write time
                event.getFinalDamage(),
                damager.getInventory().getItemInMainHand().getType().getKey().toString(),
                reach));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        Player p = event.getPlayer();
        var buf = recorder.bufferOf(p.getUniqueId(), p.getName());
        String item = event.getItem() == null
                ? "minecraft:air"
                : event.getItem().getType().getKey().toString();
        buf.push(new ItemUseEvent(buf.currentRelativeMs(), 0,
                event.getAction().name(), item));
    }
}
