package de.eternal.spigot.listener;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/** Supports /back (death location), /god (cancel damage) and /vanish (hide). */
public final class BaseListener implements Listener {

    private final EternalSpigot plugin;

    public BaseListener(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        if (plugin.backOnDeath()) {
            plugin.sessions().back.put(event.getEntity().getUniqueId(), event.getEntity().getLocation());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(@NotNull EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && plugin.sessions().god.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player joiner = event.getPlayer();
        // Re-hide everyone currently vanished from the new arrival. Deferred one
        // tick: calling hidePlayer inside PlayerJoinEvent can run before the
        // client has spawned the other players, so the hide wouldn't stick and
        // the joiner would still see vanished staff.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!joiner.isOnline()) return;
            for (Player other : plugin.getServer().getOnlinePlayers()) {
                if (!other.equals(joiner) && plugin.sessions().vanished.contains(other.getUniqueId())) {
                    joiner.hidePlayer(plugin, other);
                }
            }
        });
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        // Runtime toggles don't survive a relog; keep the /back position though.
        var u = event.getPlayer().getUniqueId();
        plugin.sessions().god.remove(u);
        plugin.sessions().vanished.remove(u);
        plugin.sessions().reply.remove(u);
        plugin.sessions().removeTpa(u);
    }
}
