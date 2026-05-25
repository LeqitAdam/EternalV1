package de.eternal.bungee.command;

import de.eternal.bungee.EternalBungee;
import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.model.PunishmentReason;
import de.eternal.core.model.PunishmentType;
import de.eternal.core.time.DurationParser;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class PunishmentActions {

    private final EternalBungee plugin;

    public PunishmentActions(@NotNull EternalBungee plugin) {
        this.plugin = plugin;
    }

    public void apply(@NotNull CommandSender issuer,
                      @NotNull UUID targetUuid,
                      @NotNull String targetName,
                      @NotNull PunishmentReason reason) {

        UUID issuerUuid = (issuer instanceof ProxiedPlayer p) ? p.getUniqueId() : null;
        String issuerName = (issuer instanceof ProxiedPlayer p) ? p.getName() : plugin.messages().get("console-name");

        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            PunishmentEntry entry = plugin.punishments().issue(
                    targetUuid, targetName, issuerUuid, issuerName, reason);
            onIssued(issuer, entry, reason);
        });
    }

    private void onIssued(@NotNull CommandSender issuer, @NotNull PunishmentEntry entry, @NotNull PunishmentReason reason) {
        String duration = reason.isPermanent() ? "permanent"
                : DurationParser.formatRemaining(reason.durationSeconds());

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
        for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) {
            if (p.hasPermission("eternal.notify") || p.hasPermission("eternal.ban") || p.hasPermission("eternal.mute")) {
                p.sendMessage(TextComponent.fromLegacyText(broadcast));
            }
        }
        plugin.getLogger().info(ChatColor.stripColor(broadcast));

        if (reason.type() == PunishmentType.BAN) {
            ProxiedPlayer online = ProxyServer.getInstance().getPlayer(entry.targetUuid());
            if (online != null) {
                String screen = plugin.messages().format("ban-kick-screen",
                        "reason", reason.label(),
                        "duration", duration,
                        "id", entry.id());
                online.disconnect(TextComponent.fromLegacyText(screen));
            }
        }
    }
}
