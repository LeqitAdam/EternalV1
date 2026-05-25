package de.eternal.spigot.command;

import de.eternal.core.config.ReasonsConfig;
import de.eternal.core.model.ReportEntry;
import de.eternal.spigot.Components;
import de.eternal.spigot.EternalSpigot;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ReportCommand implements CommandExecutor {

    private final EternalSpigot plugin;
    private final TargetResolver resolver;
    /** Shared cooldown map — also consulted by the GUI click listener so
     *  spam-clicking through the inventory can't bypass the rate limit. */
    private final ConcurrentHashMap<UUID, Long> lastReport = new ConcurrentHashMap<>();

    public ReportCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
        this.resolver = new TargetResolver(plugin.storage());
    }

    /** Exposed so {@link de.eternal.spigot.report.ReportReasonGuiListener}
     *  shares the same cooldown bookkeeping. */
    public @NotNull ConcurrentHashMap<UUID, Long> cooldownMap() {
        return lastReport;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("eternal.report")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (!(sender instanceof Player reporter)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (args.length < 1) {
            plugin.messages().send(sender, "usage-report");
            return true;
        }
        String targetName = args[0];
        if (reporter.getName().equalsIgnoreCase(targetName)) {
            plugin.messages().send(sender, "report-self");
            return true;
        }

        int cooldown = plugin.coreConfig().reports().cooldownSeconds();
        long now = System.currentTimeMillis();
        Long last = lastReport.get(reporter.getUniqueId());
        if (last != null && (now - last) < cooldown * 1000L) {
            long remaining = cooldown - (now - last) / 1000L;
            plugin.messages().send(sender, "report-cooldown", "seconds", Math.max(1, remaining));
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var maybe = resolver.resolve(targetName);
            if (maybe.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.messages().send(sender, "unknown-player", "name", targetName));
                return;
            }
            var target = maybe.get();

            // No reason → open the GUI on the main thread and stop.
            // Chat-based reason list is gone; GUI is the user-facing flow.
            if (args.length < 2) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.reportReasonGui().open(reporter, target.uuid(), target.name()));
                return;
            }

            ReasonsConfig.ReportReason reason = pickReason(args[1]);
            if (reason == null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.messages().send(sender, "unknown-reason", "id", args[1]));
                return;
            }

            String comment = args.length > 2
                    ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                    : null;

            ReportEntry created = plugin.reports().create(
                    reporter.getUniqueId(), reporter.getName(),
                    target.uuid(), target.name(),
                    reason.id(), reason.label(),
                    comment,
                    plugin.coreConfig().serverName()
            );
            lastReport.put(reporter.getUniqueId(), System.currentTimeMillis());

            Bukkit.getScheduler().runTask(plugin, () -> {
                // Freeze the back-buffer for the reported player and keep
                // recording until the report is closed/accepted. No-op when
                // EternalReplay isn't installed.
                plugin.replayBridge().captureForReport(target.uuid(), created.id());
                plugin.messages().send(sender, "report-success", "id", created.id());
                notifyStaff(created);
            });
        });
        return true;
    }

    private ReasonsConfig.ReportReason pickReason(@NotNull String arg) {
        try {
            int n = Integer.parseInt(arg);
            return plugin.reasons().reportByIndex(n);
        } catch (NumberFormatException ignored) {
            // fall through to id lookup
        }
        for (var r : plugin.reasons().reportReasons()) {
            if (r.id().equalsIgnoreCase(arg)) return r;
        }
        return null;
    }

    private void sendReasonList(@NotNull Player to, @NotNull String targetName) {
        plugin.messages().send(to, "report-prompt-header", "target", targetName);
        int i = 1;
        for (var r : plugin.reasons().reportReasons()) {
            plugin.messages().send(to, "report-prompt-line", "n", i, "label", r.label());
            i++;
        }
        plugin.messages().send(to, "report-prompt-footer", "target", targetName);
    }

    private void notifyStaff(@NotNull ReportEntry entry) {
        if (!plugin.coreConfig().reports().notifyOnlineStaff()) return;
        // Reports werden im Dashboard angenommen — die Chat-Notification
        // ist nur noch ein Hinweis, keine Action-Buttons mehr.
        String legacy = plugin.messages().format("report-staff-notify",
                "id", entry.id(),
                "target", entry.targetName(),
                "reason", entry.reasonLabel(),
                "reporter", entry.reporterName());
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("eternal.report.notify")) p.sendMessage(legacy);
        }
    }

    private @org.jetbrains.annotations.NotNull BaseComponent clickableAction(
            @org.jetbrains.annotations.NotNull String legacyLabel,
            @org.jetbrains.annotations.NotNull String runCommand,
            @org.jetbrains.annotations.NotNull String hoverLegacy) {
        TextComponent c = Components.legacy(legacyLabel);
        c.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, runCommand));
        c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(TextComponent.fromLegacyText(hoverLegacy))));
        return c;
    }
}
