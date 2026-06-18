package de.eternal.autonicker.listener;

import de.eternal.autonicker.EternalAutonicker;
import de.eternal.autonicker.item.NickItems;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/** Gives the nametag on join, toggles the nick on right-click, locks the slot. */
public final class NickItemListener implements Listener {

    private final EternalAutonicker plugin;

    public NickItemListener(@NotNull EternalAutonicker plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        if (!plugin.config().enabled() || !plugin.config().giveTagOnJoin()) return;
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) plugin.nickService().giveTag(p);
        }, 20L);
    }

    @EventHandler
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        if (!NickItems.isTag(plugin, p.getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
        plugin.nickService().toggle(p);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        if (!plugin.config().lockTagSlot()) return;
        if (NickItems.isTag(plugin, event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(@NotNull InventoryClickEvent event) {
        if (!plugin.config().lockTagSlot()) return;
        if (NickItems.isTag(plugin, event.getCurrentItem()) || NickItems.isTag(plugin, event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        plugin.nickService().handleQuit(event.getPlayer().getUniqueId());
    }
}
