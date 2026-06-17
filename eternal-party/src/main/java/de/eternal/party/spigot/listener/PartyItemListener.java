package de.eternal.party.spigot.listener;

import de.eternal.party.spigot.EternalPartySpigot;
import de.eternal.party.spigot.item.PartyItems;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the hotbar party head: gives it on join, opens the menu on right-click,
 * turns a hit/right-click on another player into an invite or friend request,
 * and (optionally) locks the slot so the item can't be dropped or moved.
 */
public final class PartyItemListener implements Listener {

    /** Debounce window — a single left-click can fire both interact and damage
     *  events; this stops the invite from being sent twice. */
    private static final long COOLDOWN_MS = 600L;

    private final EternalPartySpigot plugin;
    private final ConcurrentHashMap<UUID, Long> lastHit = new ConcurrentHashMap<>();

    public PartyItemListener(@NotNull EternalPartySpigot plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        if (!plugin.partyConfig().enabled() || !plugin.partyConfig().giveHeadOnJoin()) return;
        Player p = event.getPlayer();
        // Delay so the player's inventory + any starter-kit plugins have settled.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) plugin.service().giveHead(p);
        }, 20L);
    }

    @EventHandler
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return; // ignore the off-hand duplicate
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        if (!PartyItems.isHead(plugin, p.getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
        plugin.service().openMenu(p);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(@NotNull EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player target)) return;
        if (!PartyItems.isHead(plugin, attacker.getInventory().getItemInMainHand())) return;
        event.setCancelled(true); // the head never actually deals damage
        if (claim(attacker.getUniqueId())) plugin.service().headInteract(attacker, target);
    }

    @EventHandler
    public void onRightClickPlayer(@NotNull PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player attacker = event.getPlayer();
        if (!(event.getRightClicked() instanceof Player target)) return;
        if (!PartyItems.isHead(plugin, attacker.getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
        if (claim(attacker.getUniqueId())) plugin.service().headInteract(attacker, target);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        if (!plugin.partyConfig().lockHeadSlot()) return;
        if (PartyItems.isHead(plugin, event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(@NotNull InventoryClickEvent event) {
        if (!plugin.partyConfig().lockHeadSlot()) return;
        // Don't interfere with our own menus — those are handled (and cancelled)
        // by PartyMenuListener; only guard the head sitting in the player inv.
        if (event.getView().getTopInventory().getHolder() instanceof de.eternal.party.spigot.gui.PartyHolder) return;
        if (PartyItems.isHead(plugin, event.getCurrentItem()) || PartyItems.isHead(plugin, event.getCursor())) {
            event.setCancelled(true);
        }
    }

    /** True if this player may fire a head action now (and stamps the time). */
    private boolean claim(@NotNull UUID uuid) {
        long now = System.currentTimeMillis();
        Long prev = lastHit.get(uuid);
        if (prev != null && now - prev < COOLDOWN_MS) return false;
        lastHit.put(uuid, now);
        return true;
    }
}
