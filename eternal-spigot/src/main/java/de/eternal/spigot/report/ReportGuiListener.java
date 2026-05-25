package de.eternal.spigot.report;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Wires clicks in the {@link ReportListGui} chest to {@link ReportActions}.
 *
 * - Left-click   -> claim + auto-teleport
 * - Shift-click  -> close with default resolution
 *
 * No commands are run — we call ReportActions directly so the user-typed
 * /reportsystem only exposes login/logout/list.
 */
public final class ReportGuiListener implements Listener {

    private final EternalSpigot plugin;
    private final ReportListGui gui;
    private final ReportActions actions;

    public ReportGuiListener(@NotNull EternalSpigot plugin,
                              @NotNull ReportListGui gui,
                              @NotNull ReportActions actions) {
        this.plugin = plugin;
        this.gui = gui;
        this.actions = actions;
    }

    @EventHandler
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ReportListGui.Holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player mod)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) return;
        var maybeId = gui.readReportId(clicked);
        if (maybeId.isEmpty()) return;
        long id = maybeId.get();

        mod.closeInventory();
        ClickType type = event.getClick();
        if (type.isShiftClick()) {
            actions.close(mod, id, "Bearbeitet von " + mod.getName());
        } else {
            actions.claim(mod, id);
        }
    }
}
