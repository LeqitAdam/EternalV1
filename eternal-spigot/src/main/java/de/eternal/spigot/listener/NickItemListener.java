package de.eternal.spigot.listener;

import de.eternal.spigot.EternalSpigot;
import de.eternal.spigot.nick.NickItems;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/** Gives the nametag on join, toggles the nick on right-click, locks the slot. */
public final class NickItemListener implements Listener {

    /** Permission gate — only staff/team may nick (default: op, see plugin.yml). */
    public static final String USE_PERM = "eternal.autonick.use";

    private final EternalSpigot plugin;

    public NickItemListener(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        if (!plugin.autonicker().enabled()) return;
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (!p.hasPermission(USE_PERM)) {
                // No permission → strip any leftover nick item (e.g. handed out
                // before the perm gate, still saved in the inventory).
                plugin.nickService().removeTag(p);
            } else if (plugin.autonicker().giveTagOnJoin()) {
                plugin.nickService().giveTag(p);
            }
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
        if (!p.hasPermission(USE_PERM)) return; // no perm → item does nothing
        plugin.nickService().toggle(p);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        if (!plugin.autonicker().lockTagSlot()) return;
        if (NickItems.isTag(plugin, event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(@NotNull InventoryClickEvent event) {
        if (!plugin.autonicker().lockTagSlot()) return;
        if (NickItems.isTag(plugin, event.getCurrentItem()) || NickItems.isTag(plugin, event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        plugin.nickService().handleQuit(event.getPlayer().getUniqueId());
    }

    /** Chat shows the real name with many chat plugins (they format with the
     *  login name, which a profile swap can't change). Run late + rewrite the
     *  real name → fake name in the final chat format. No-op when the chat
     *  plugin cancels the event and sends its own message. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(@NotNull AsyncPlayerChatEvent event) {
        String fake = plugin.nickService().nickNameOf(event.getPlayer().getUniqueId());
        if (fake == null) return;
        String real = event.getPlayer().getName();
        if (!fake.equals(real) && event.getFormat().contains(real)) {
            event.setFormat(event.getFormat().replace(real, fake));
        }
    }
}
