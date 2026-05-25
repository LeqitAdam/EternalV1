package de.eternal.bungee.command;

import de.eternal.bungee.EternalBungee;
import de.eternal.core.model.PlayerProfile;
import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.model.PunishmentType;
import de.eternal.core.model.ReportEntry;
import de.eternal.core.model.ReportStatus;
import de.eternal.core.time.DurationParser;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bungee {@code /history} — DKBans-style detail cards. Same shape as the
 * Spigot version; lives here so a single proxy restart deploys it everywhere.
 */
public final class HistoryCommand extends Command {

    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final EternalBungee plugin;
    private final TargetResolver resolver;

    public HistoryCommand(@NotNull EternalBungee plugin) {
        super("history", "eternal.lookup", "banhistory");
        this.plugin = plugin;
        this.resolver = new TargetResolver(plugin.storage());
    }

    @Override
    public void execute(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 1) {
            plugin.messages().send(sender, "usage-history");
            return;
        }
        String name = args[0];

        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            var maybe = resolver.resolve(name);
            if (maybe.isEmpty()) {
                plugin.messages().send(sender, "unknown-player", "name", name);
                return;
            }
            var target = maybe.get();
            List<PunishmentEntry> bans = plugin.punishments().history(target.uuid(), null);
            List<ReportEntry> reports = plugin.storage().findReportsByTarget(target.uuid());

            List<Row> rows = new ArrayList<>(bans.size() + reports.size());
            for (PunishmentEntry e : bans) rows.add(Row.fromPunishment(e));
            for (ReportEntry r : reports) {
                Long banId = plugin.storage().findBanForReport(r.id()).orElse(null);
                rows.add(Row.fromReport(r, banId));
            }
            rows.sort(Comparator.comparing(Row::timestamp).reversed());

            Set<UUID> staffUuids = new HashSet<>();
            for (Row row : rows) if (row.staffUuid != null) staffUuids.add(row.staffUuid);
            for (Row row : rows) if (row.modifierUuid != null) staffUuids.add(row.modifierUuid);
            Map<UUID, String> displayByUuid = new HashMap<>();
            for (UUID u : staffUuids) {
                String d = plugin.storage().findProfile(u)
                        .map(PlayerProfile::lastDisplayName).orElse("");
                displayByUuid.put(u, d);
            }

            PlayerProfile targetProfile = plugin.storage().findProfile(target.uuid()).orElse(null);
            String targetGroup = (targetProfile == null || targetProfile.lastGroupName().isEmpty())
                    ? "Spieler" : targetProfile.lastGroupName();
            String targetDisplay = (targetProfile != null && !targetProfile.lastDisplayName().isBlank())
                    ? targetProfile.lastDisplayName()
                    : "&8[&f" + targetGroup + "&8] &e" + target.name();

            Map<Long, String> banLabelById = new HashMap<>();
            for (PunishmentEntry e : bans) banLabelById.put(e.id(), e.reasonLabel());

            plugin.messages().send(sender, "history-card-header", "display", targetDisplay);
            if (rows.isEmpty()) {
                plugin.messages().send(sender, "lookup-history-empty");
                return;
            }
            for (Row row : rows) sendRow(sender, row, displayByUuid, banLabelById);
            plugin.messages().send(sender, "history-separator");
        });
    }

    private void sendRow(@NotNull CommandSender sender, @NotNull Row row,
                         @NotNull Map<UUID, String> displayByUuid,
                         @NotNull Map<Long, String> banLabelById) {
        plugin.messages().send(sender, "history-separator");
        plugin.messages().send(sender, "history-card-line-id", "id", row.id);

        String typeKey = row.isReport
                ? "history-card-line-type-report"
                : (row.type == PunishmentType.BAN
                    ? "history-card-line-type-ban"
                    : "history-card-line-type-mute");
        plugin.messages().send(sender, typeKey);

        if (row.isActive) plugin.messages().send(sender, "history-card-line-active-yes");
        else plugin.messages().send(sender, "history-card-line-active-no",
                "state", plugin.messages().format(row.stateKey));

        sendStaffField(sender,
                row.isReport ? "history-card-line-reporter-prefix" : "history-card-line-staff-prefix",
                row.staffName,
                row.staffUuid == null ? "" : displayByUuid.getOrDefault(row.staffUuid, ""));

        plugin.messages().send(sender, "history-card-line-reason", "label", row.label);

        if (!row.isReport) {
            plugin.messages().send(sender, "history-card-line-duration",
                    "value", row.durationLabel == null ? "-" : row.durationLabel);
            plugin.messages().send(sender, "history-card-line-remaining",
                    "value", row.remainingLabel == null ? "-" : row.remainingLabel);
        }
        plugin.messages().send(sender, "history-card-line-issued",
                "value", DATE.format(row.timestamp));
        if (row.timeout != null) {
            plugin.messages().send(sender, "history-card-line-timeout",
                    "value", DATE.format(row.timeout));
        }

        if (row.isReport) {
            if (row.banId != null) {
                String banLabel = banLabelById.getOrDefault(row.banId, "?");
                plugin.messages().send(sender, "history-card-line-result-ban",
                        "id", row.banId, "label", banLabel);
            } else {
                plugin.messages().send(sender, "history-card-line-result-none");
            }
        }
        if (row.modifiedAt != null) {
            plugin.messages().send(sender, "history-card-line-modified-time",
                    "value", DATE.format(row.modifiedAt));
            sendStaffField(sender, "history-card-line-modified-by-prefix",
                    row.modifierName == null ? "?" : row.modifierName,
                    row.modifierUuid == null ? "" : displayByUuid.getOrDefault(row.modifierUuid, ""));
        }
    }

    private void sendStaffField(@NotNull CommandSender sender, @NotNull String prefixKey,
                                 @NotNull String staffName, @NotNull String displayLegacy) {
        String prefix = plugin.messages().format(prefixKey);
        List<BaseComponent> out = new ArrayList<>();
        for (BaseComponent c : TextComponent.fromLegacyText(prefix)) out.add(c);
        out.add(LookupCommand.clickableNameStatic(plugin, staffName, displayLegacy));
        sender.sendMessage(out.toArray(new BaseComponent[0]));
    }

    private record Row(
            boolean isReport,
            Instant timestamp,
            PunishmentType type,
            boolean isActive,
            String stateKey,
            long id,
            String label,
            String staffName,
            UUID staffUuid,
            String durationLabel,
            String remainingLabel,
            Instant timeout,
            Long banId,
            Instant modifiedAt,
            UUID modifierUuid,
            String modifierName
    ) {
        static Row fromPunishment(PunishmentEntry e) {
            String stateKey = e.active() ? "lookup-state-active"
                    : (e.pardonedAt() != null ? "history-state-pardoned" : "history-state-expired");
            String dur, remaining;
            if (e.isPermanent()) {
                dur = "permanent";
                remaining = e.active() ? "permanent" : "-";
            } else {
                long total = e.expiresAt().getEpochSecond() - e.issuedAt().getEpochSecond();
                dur = DurationParser.formatRemaining(total);
                long left = e.expiresAt().getEpochSecond() - Instant.now().getEpochSecond();
                remaining = (e.active() && left > 0) ? DurationParser.formatRemaining(left) : "-";
            }
            return new Row(false, e.issuedAt(), e.type(), e.active(), stateKey, e.id(),
                    e.reasonLabel(), e.issuerName(), e.issuerUuid(),
                    dur, remaining, e.expiresAt(), null,
                    e.modifiedAt(), e.modifiedByUuid(), e.modifiedByName());
        }

        static Row fromReport(ReportEntry r, Long banId) {
            String stateKey = switch (r.status()) {
                case OPEN -> "history-state-report-open";
                case CLAIMED -> "history-state-report-claimed";
                case CLOSED -> "history-state-report-closed";
            };
            boolean active = r.status() != ReportStatus.CLOSED;
            return new Row(true, r.createdAt(), null, active, stateKey, r.id(),
                    r.reasonLabel(), r.reporterName(), r.reporterUuid(),
                    null, null, null, banId, null, null, null);
        }
    }
}
