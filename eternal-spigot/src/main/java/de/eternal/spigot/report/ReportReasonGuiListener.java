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
                // Start the in-flight replay capture; the returned id is
                // the FINAL replay-id (already reserved in the API even
                // before endCapture finishes persisting). Write it back
                // onto the report row immediately so /history can link to
                // it from the moment the report appears.
                plugin.replayBridge().captureForReport(sub.targetUuid(), created.id())
                        .ifPresent(rid -> linkReplayToReport(created.id(), rid));
                plugin.messages().send(reporter, "report-success", "id", created.id());
                notifyStaff(created);
            });
        });
    }

    /** Writes replay-id back onto the report row when storage supports
     *  it. Fail-soft — older storage backends without the column won't
     *  block the report flow. */
    private void linkReplayToReport(long reportId, long replayId) {
        if (plugin.storage() instanceof de.eternal.core.storage.sql.SqlStorage sql) {
            sql.linkReportToReplay(reportId, replayId);
        }
    }

    /** Simple chat notification — Reports werden im Dashboard angenommen. */
    private void notifyStaff(@NotNull ReportEntry entry) {
        if (!plugin.coreConfig().reports().notifyOnlineStaff()) return;
        String legacy = plugin.messages().format("report-staff-notify",
                "id", entry.id(),
                "target", entry.targetName(),
                "reason", entry.reasonLabel(),
                "reporter", entry.reporterName());
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("eternal.report.notify")) p.sendMessage(legacy);
        }
    }
}
