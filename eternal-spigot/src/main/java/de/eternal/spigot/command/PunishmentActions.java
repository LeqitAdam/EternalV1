package de.eternal.spigot.command;

import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.model.PunishmentReason;
import de.eternal.core.model.PunishmentType;
import de.eternal.core.model.ReportEntry;
import de.eternal.core.model.ReportStatus;
import de.eternal.core.time.DurationParser;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Applies a {@link PunishmentReason} once the dispatcher has resolved + tier-
 * checked it. Writes to the DB async, then renders the kick / broadcast back
 * on the main thread.
 */
public final class PunishmentActions {

    private final EternalSpigot plugin;

    public PunishmentActions(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    public void apply(@NotNull CommandSender issuer,
                      @NotNull UUID targetUuid,
                      @NotNull String targetName,
                      @NotNull PunishmentReason reason) {

        UUID issuerUuid = (issuer instanceof Player p) ? p.getUniqueId() : null;
        String issuerName = (issuer instanceof Player p) ? p.getName() : plugin.messages().get("console-name");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PunishmentEntry entry = plugin.punishments().issue(
                    targetUuid, targetName, issuerUuid, issuerName, reason);

            // Bans schliessen alle offenen/claimed Reports gegen das Ziel automatisch
            // UND verlinken den Bann zurueck. So sehen wir in der History was passierte.
            if (reason.type() == PunishmentType.BAN) {
                autoCloseReports(targetUuid, reason.label(), issuerName, entry.id());
            }

            Bukkit.getScheduler().runTask(plugin, () -> onIssued(issuer, entry, reason));
        });
    }

    private void autoCloseReports(@NotNull UUID targetUuid, @NotNull String banLabel,
                                   @NotNull String issuerName, long banId) {
        for (ReportEntry r : plugin.storage().findReportsByTarget(targetUuid)) {
            if (r.status() == ReportStatus.OPEN || r.status() == ReportStatus.CLAIMED) {
                plugin.storage().closeReport(r.id(),
                        "Auto-Close: gebannt fuer '" + banLabel + "' durch " + issuerName);
                plugin.storage().linkReportToBan(r.id(), banId);
            }
        }
    }

    private void onIssued(@NotNull CommandSender issuer, @NotNull PunishmentEntry entry, @NotNull PunishmentReason reason) {
        // entry.expiresAt() reflects the ACTUAL applied duration (after
        // escalation kicked in), not necessarily the reason's default — read
        // from the persisted entry, not the reason record.
        String duration;
        if (entry.isPermanent()) {
            duration = "permanent";
        } else {
            long secs = entry.expiresAt().getEpochSecond() - entry.issuedAt().getEpochSecond();
            duration = DurationParser.formatRemaining(secs);
        }

        String successKey = reason.type() == PunishmentType.BAN ? "ban-success" : "mute-success";
        String broadcastKey = reason.type() == PunishmentType.BAN ? "ban-broadcast" : "mute-broadcast";

        plugin.messages().send(issuer, successKey,
                "target", entry.targetName(),
                "reason", reason.label(),
                "duration", duration);

        String broadcast = plugin.messages().format(broadcastKey,
                "target", entry.targetName(),
                "issuer", entry.issuerName(),
                "reason", reason.label(),
                "duration", duration);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("eternal.notify")
                    || p.hasPermission("eternal.ban")
                    || p.hasPermission("eternal.mute")) {
                p.sendMessage(broadcast);
            }
        }
        plugin.getLogger().info(ChatColor.stripColor(broadcast));

        if (reason.type() == PunishmentType.BAN) {
            Player online = Bukkit.getPlayer(entry.targetUuid());
            if (online != null) {
                String screen = plugin.messages().format("ban-kick-screen",
                        "reason", reason.label(),
                        "duration", duration,
                        "id", entry.id());
                online.kickPlayer(screen);
            }
        }
    }
}
