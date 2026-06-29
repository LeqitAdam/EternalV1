package de.eternal.bungee.command;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
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

public final class ReportCommand extends Command implements net.md_5.bungee.api.plugin.TabExecutor {

    /** Matches the channel name registered in
     *  {@code ReportGuiRequestListener.CHANNEL} on the Spigot side. */
    private static final String GUI_CHANNEL = "eternal:open-report-gui";
    /** Matches {@code CaptureReplayRequestListener.CHANNEL}. */
    private static final String CAPTURE_CHANNEL = "eternal:start-replay-capture";

    private final EternalBungee plugin;
    private final TargetResolver resolver;
    private final ConcurrentHashMap<UUID, Long> lastReport = new ConcurrentHashMap<>();

    public ReportCommand(@NotNull EternalBungee plugin) {
        super("report", "eternal.report");
        this.plugin = plugin;
        this.resolver = new TargetResolver(plugin.storage());
        // Register both outgoing channels so Bungee allows sendData on
        // them. Without this, sendData() throws ChannelNotRegisteredException.
        ProxyServer.getInstance().registerChannel(GUI_CHANNEL);
        ProxyServer.getInstance().registerChannel(CAPTURE_CHANNEL);
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
                // No reason argument — delegate to the Spigot GUI. Bungee
                // can't open inventories, so we send a plugin-message to
                // the reporter's current backend and let the Spigot-side
                // ReportGuiRequestListener handle the actual openInventory
                // call. The legacy chat-based reason list is gone.
                openGuiOnBackend(reporter, target.uuid(), target.name());
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

            // Start the in-flight replay capture on whichever backend
            // the reportee is currently on. WITHOUT this, no recording
            // is started for this report — and when the mod later
            // accepts via the web, tryPlayForReport falls back to
            // captureNow on the MOD's backend, which can be empty if
            // the target is on a different server. Triggers must run
            // on the target's backend, so we route via plugin-message.
            startReplayCaptureOnTargetBackend(onlineTarget, target.uuid(), created.id());

            plugin.messages().send(sender, "report-success", "id", created.id());
            notifyStaff(created);
        });
    }

    /** Fires a plugin-message at the target's current backend asking it
     *  to start the in-flight replay capture for {@code reportId}. No-op
     *  when the target isn't online — there's nothing to record. */
    private void startReplayCaptureOnTargetBackend(@org.jetbrains.annotations.Nullable ProxiedPlayer target,
                                                    @NotNull UUID targetUuid, long reportId) {
        if (target == null || target.getServer() == null) return;
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("start-capture");
        out.writeUTF(targetUuid.toString());
        out.writeLong(reportId);
        target.getServer().sendData(CAPTURE_CHANNEL, out.toByteArray());
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

    /** Sends a plugin-message to the reporter's current backend Spigot
     *  asking it to open the report-reason GUI for the target. The Spigot-
     *  side {@code ReportGuiRequestListener} picks this up and calls
     *  {@code openInventory} on the main thread.
     *
     *  <p>If the player isn't on any backend (very narrow race window
     *  between login and server-connect) we silently no-op — there is no
     *  inventory to open without a backend.</p> */
    private void openGuiOnBackend(@NotNull ProxiedPlayer reporter,
                                   @NotNull UUID targetUuid, @NotNull String targetName) {
        if (reporter.getServer() == null) {
            reporter.sendMessage(TextComponent.fromLegacyText(
                    ChatColor.translateAlternateColorCodes('&',
                            "&cKein Backend-Server verbunden — bitte erneut versuchen.")));
            return;
        }
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("open-report-gui");
        out.writeUTF(targetUuid.toString());
        out.writeUTF(targetName);
        // sendData() routes the bytes to the backend the player is on.
        // The byte payload reaches the Spigot listener via Bukkit's
        // PluginMessageListener API on the matching channel name.
        reporter.getServer().sendData(GUI_CHANNEL, out.toByteArray());
    }

    private void notifyStaff(@NotNull ReportEntry entry) {
        if (!plugin.coreConfig().reports().notifyOnlineStaff()) return;
        // In-game: show the FAKE name of a nicked target so the disguise isn't
        // leaked in chat. The DB keeps the real name (website shows both).
        String shownTarget = entry.targetName();
        if (plugin.storage() instanceof de.eternal.core.social.SocialStorage s) {
            var ns = s.findNickSession(entry.targetUuid());
            if (ns.isPresent() && !ns.get().nickName().isEmpty()) shownTarget = ns.get().nickName();
        }
        String msg = plugin.messages().format("report-staff-notify",
                "id", entry.id(),
                "target", shownTarget,
                "reason", entry.reasonLabel(),
                "reporter", entry.reporterName());
        for (UUID u : plugin.staff().snapshot()) {
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(u);
            if (p != null) p.sendMessage(TextComponent.fromLegacyText(msg));
        }
    }
    @Override
    public Iterable<String> onTabComplete(net.md_5.bungee.api.CommandSender sender, String[] args) {
        return BungeeTab.players(plugin, args);
    }
}
