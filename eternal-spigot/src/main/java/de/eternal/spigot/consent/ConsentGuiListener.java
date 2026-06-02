package de.eternal.spigot.consent;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Wires clicks on the {@link ConsentGui} chest to {@link ConsentService}
 * accept/decline, and re-opens the GUI when a pending player tries to
 * escape it without choosing. The pending player has no other way to
 * play (movement is frozen, chat blocked, every command except {@code
 * /eternal} is suppressed) — closing the GUI without a decision would
 * just leave them stuck staring at an inventory hotbar.
 */
public final class ConsentGuiListener implements Listener {

    private final EternalSpigot plugin;
    private final ConsentGui gui;

    public ConsentGuiListener(@NotNull EternalSpigot plugin, @NotNull ConsentGui gui) {
        this.plugin = plugin;
        this.gui = gui;
    }

    /** Handle the click. We cancel always (so the player can't pick
     *  the concrete blocks up) and dispatch on the PDC tag. The info
     *  item has no tag — clicking it does nothing besides the cancel. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ConsentGui.Holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) return;
        ConsentGui.Choice choice = gui.choiceOf(clicked);
        if (choice == null) return; // info-item click — no-op

        switch (choice) {
            case ACCEPT -> {
                // Close the GUI on the same tick we accept — otherwise
                // the player sees a flash of the action items going
                // dim while ConsentService finishes its async writes.
                player.closeInventory();
                plugin.consent().accept(player);
                plugin.messages().send(player, "consent-accepted");
            }
            case DECLINE -> {
                // closeInventory is implied by the kick. Use the
                // multi-line translation as the kick screen.
                String kick = plugin.messages().format("consent-declined-kick");
                plugin.consent().decline(player, kick);
            }
        }
    }

    /**
     * If the player closes the GUI without picking (ESC / E key), and
     * they're still pending, re-open it on the next tick. The 1-tick
     * delay is mandatory: Bukkit refuses to open a new inventory from
     * within an InventoryCloseEvent handler — the close is still
     * processing when we'd try to open.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ConsentGui.Holder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!plugin.consent().isPending(player.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Re-check pending — they might have accepted via /eternal
            // accept fallback between close and our re-open task.
            if (player.isOnline() && plugin.consent().isPending(player.getUniqueId())) {
                gui.open(player);
            }
        }, 1L);
    }
}
