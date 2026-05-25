package de.eternal.bungee.command;

import de.eternal.bungee.EternalBungee;
import de.eternal.core.config.ReasonsConfig;
import de.eternal.core.model.ReportEntry;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ReportCommand extends Command {

    private final EternalBungee plugin;
    private final TargetResolver resolver;
    private final ConcurrentHashMap<UUID, Long> lastReport = new ConcurrentHashMap<>();

    public ReportCommand(@NotNull EternalBungee plugin) {
        super("report", "eternal.report");
        this.plugin = plugin;
        this.resolver = new TargetResolver(plugin.storage());
    }

    @Override
    public void execute(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof ProxiedPlayer reporter)) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        if (args.length < 1) {
            sender.sendMessage(TextComponent.fromLegacyText("/report <Spieler> [reason-id-oder-Nr] [Kommentar]"));
            return;
        }
        if (reporter.getName().equalsIgnoreCase(args[0])) {
            plugin.messages().send(sender, "report-self");
            return;
        }

        int cooldown = plugin.coreConfig().reports().cooldownSeconds();
        long now = System.currentTimeMillis();
        Long last = lastReport.get(reporter.getUniqueId());
        if (last != null && (now - last) < cooldown * 1000L) {
            plugin.messages().send(sender, "report-cooldown",
                    "seconds", Math.max(1, cooldown - (now - last) / 1000L));
            return;
        }

        String targetName = args[0];
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            var maybe = resolver.resolve(targetName);
            if (maybe.isEmpty()) {
                plugin.messages().send(sender, "unknown-player", "name", targetName);
                return;
            }
            var target = maybe.get();
            if (args.length < 2) {
                sendReasonList(reporter, target.name());
                return;
            }
            ReasonsConfig.ReportReason reason = pickReason(args[1]);
            if (reason == null) {
                sender.sendMessage(TextComponent.fromLegacyText("§cUnbekannter Grund: " + args[1]));
                return;
            }
            String comment = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : null;

            ProxiedPlayer onlineTarget = ProxyServer.getInstance().getPlayer(target.uuid());
            String serverName = onlineTarget != null && onlineTarget.getServer() != null
                    ? onlineTarget.getServer().getInfo().getName()
                    : plugin.coreConfig().serverName();

            ReportEntry created = plugin.reports().create(
                    reporter.getUniqueId(), reporter.getName(),
                    target.uuid(), target.name(),
                    reason.id(), reason.label(),
                    comment,
                    serverName);
            lastReport.put(reporter.getUniqueId(), System.currentTimeMillis());

            plugin.messages().send(sender, "report-success", "id", created.id());
            notifyStaff(created);
        });
    }

    private ReasonsConfig.ReportReason pickReason(@NotNull String arg) {
        try {
            int n = Integer.parseInt(arg);
            return plugin.reasons().reportByIndex(n);
        } catch (NumberFormatException ignored) { /* fall through */ }
        for (var r : plugin.reasons().reportReasons()) {
            if (r.id().equalsIgnoreCase(arg)) return r;
        }
        return null;
    }

    private void sendReasonList(@NotNull ProxiedPlayer to, @NotNull String targetName) {
        plugin.messages().send(to, "report-prompt-header", "target", targetName);
        int i = 1;
        for (var r : plugin.reasons().reportReasons()) {
            to.sendMessage(TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&',
                    "&7  &e" + i + ". &f" + r.label())));
            i++;
        }
        to.sendMessage(TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&',
                "&7&oNochmal mit: &c/report " + targetName + " <Nr> [Kommentar]")));
    }

    private void notifyStaff(@NotNull ReportEntry entry) {
        if (!plugin.coreConfig().reports().notifyOnlineStaff()) return;
        String msg = plugin.messages().format("report-staff-notify",
                "id", entry.id(),
                "target", entry.targetName(),
                "reason", entry.reasonLabel(),
                "reporter", entry.reporterName());
        for (UUID u : plugin.staff().snapshot()) {
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(u);
            if (p != null) p.sendMessage(TextComponent.fromLegacyText(msg));
        }
    }
}
