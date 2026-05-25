package de.eternal.spigot.report;

import de.eternal.core.model.ReportEntry;
import de.eternal.spigot.EternalSpigot;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wires clicks in the {@link ReportReasonGui} chest to report submission.
 * Cooldown-tracking + staff-notify mirror what /report &lt;player&gt; &lt;id&gt;
 * did in chat, so the GUI is a 1:1 replacement.
 */
public final class ReportReasonGuiListener implements Listener {

    private final EternalSpigot plugin;
    private final ReportReasonGui gui;
    private final ConcurrentHashMap<UUID, Long> lastReport;

    public ReportReasonGuiListener(@NotNull EternalSpigot plugin,
                                   @NotNull ReportReasonGui gui,
                                   @NotNull ConcurrentHashMap<UUID, Long> lastReport) {
        this.plugin = plugin;
        this.gui = gui;
        this.lastReport = lastReport;
    }

    @EventHandler
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ReportReasonGui.Holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player reporter)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) return;
        var maybe = gui.readClick(clicked);
        if (maybe.isEmpty()) return;
        var sub = maybe.get();

        reporter.closeInventory();

        // Cooldown gate — re-checked here because the GUI can sit open while
        // the player spam-clicks reasons.
        int cooldown = plugin.coreConfig().reports().cooldownSeconds();
        long now = System.currentTimeMillis();
        Long last = lastReport.get(reporter.getUniqueId());
        if (last != null && (now - last) < cooldown * 1000L) {
            long remaining = cooldown - (now - last) / 1000L;
            plugin.messages().send(reporter, "report-cooldown", "seconds", Math.max(1, remaining));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ReportEntry created = plugin.reports().create(
                    reporter.getUniqueId(), reporter.getName(),
                    sub.targetUuid(), sub.targetName(),
                    sub.reasonId(), sub.reasonLabel(),
                    null, // no comment for GUI submission
                    plugin.coreConfig().serverName()
            );
            lastReport.put(reporter.getUniqueId(), System.currentTimeMillis());
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.messages().send(reporter, "report-success", "id", created.id());
                notifyStaff(created);
            });
        });
    }

    /** Same staff-notify formatting as ReportCommand.notifyStaff — kept in
     *  sync deliberately so chat and GUI submissions look identical. */
    private void notifyStaff(@NotNull ReportEntry entry) {
        if (!plugin.coreConfig().reports().notifyOnlineStaff()) return;
        String legacy = plugin.messages().format("report-staff-notify",
                "id", entry.id(),
                "target", entry.targetName(),
                "reason", entry.reasonLabel(),
                "reporter", entry.reporterName());

        BaseComponent accept = clickable(
                plugin.messages().format("report-notify-accept"),
                "/eternalreport accept " + entry.id(),
                plugin.messages().format("hover-accept-report", "id", entry.id()));
        BaseComponent reject = clickable(
                plugin.messages().format("report-notify-reject"),
                "/eternalreport reject " + entry.id(),
                plugin.messages().format("hover-reject-report", "id", entry.id()));
        BaseComponent tp = clickable(
                plugin.messages().format("report-notify-tp"),
                "/eternalreport tp " + entry.id(),
                plugin.messages().format("hover-tp-report", "id", entry.id()));

        BaseComponent[] components = de.eternal.spigot.Components.concat(
                legacy + " ", accept, " ", reject, " ", tp);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("eternal.report.notify")) p.spigot().sendMessage(components);
        }
    }

    private @NotNull BaseComponent clickable(@NotNull String legacy, @NotNull String cmd,
                                              @NotNull String hover) {
        TextComponent c = de.eternal.spigot.Components.legacy(legacy);
        c.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
        c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(TextComponent.fromLegacyText(hover))));
        return c;
    }
}
