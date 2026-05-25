package de.eternal.spigot.command;

import de.eternal.core.model.PlayerProfile;
import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.model.PunishmentType;
import de.eternal.core.model.ReportEntry;
import de.eternal.core.time.DurationParser;
import de.eternal.spigot.Components;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /history — JSON-artige, klar gegliederte Auflistung aller Bans, Mutes und
 * Reports eines Spielers. Jeder Eintrag ist eine Mini-Card mit Trennlinie
 * davor, klickbarem Issuer-/Reporter-Name (mit Rang-Prefix) und einer
 * "Resultat: BAN #X"-Zeile fuer Reports, die zu einem Bann gefuehrt haben.
 */
public final class HistoryCommand implements CommandExecutor {

    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final EternalSpigot plugin;
    private final TargetResolver resolver;

    public HistoryCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
        this.resolver = new TargetResolver(plugin.storage());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("eternal.lookup")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length < 1) {
            plugin.messages().send(sender, "usage-history");
            return true;
        }
        String name = args[0];

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var maybe = resolver.resolve(name);
            if (maybe.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.messages().send(sender, "unknown-player", "name", name));
                return;
            }
            var target = maybe.get();

            // /history is read-only — no tier check, just permission.
            {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    List<PunishmentEntry> bans = plugin.punishments().history(target.uuid(), null);
                    List<ReportEntry> reports = plugin.storage().findReportsByTarget(target.uuid());

                    List<Row> rows = new ArrayList<>(bans.size() + reports.size());
                    for (PunishmentEntry e : bans) rows.add(Row.fromPunishment(e));
                    for (ReportEntry r : reports) {
                        Long banId = plugin.storage().findBanForReport(r.id()).orElse(null);
                        rows.add(Row.fromReport(r, banId));
                    }
                    rows.sort(Comparator.comparing(Row::timestamp).reversed());

                    // Pre-fetch staff profiles for rank prefix AND cached
                    // DisplayName (rank-coloured form from CloudNet-Chat). One
                    // DB query per unique staff UUID, no thread hopping.
                    Map<UUID, String> groupByUuid = new HashMap<>();
                    Map<UUID, String> displayByUuid = new HashMap<>();
                    java.util.Set<UUID> staffUuids = new java.util.HashSet<>();
                    for (Row row : rows) if (row.staffUuid() != null) staffUuids.add(row.staffUuid());
                    for (Row row : rows) if (row.modifierUuid() != null) staffUuids.add(row.modifierUuid());
                    for (UUID u : staffUuids) {
                        PlayerProfile pp = plugin.storage().findProfile(u).orElse(null);
                        groupByUuid.put(u, pp == null ? "" : pp.lastGroupName());
                        displayByUuid.put(u, pp == null ? "" : pp.lastDisplayName());
                    }
                    PlayerProfile targetProfile = plugin.storage().findProfile(target.uuid()).orElse(null);
                    String targetGroup = (targetProfile == null || targetProfile.lastGroupName().isEmpty())
                            ? "Spieler" : targetProfile.lastGroupName();
                    // Cached display is safe async; online lookup happens on
                    // main thread inside render() to avoid Paper's
                    // async-getDisplayName check that silently aborts the
                    // worker.
                    String cachedDisplay = (targetProfile != null) ? targetProfile.lastDisplayName() : "";
                    // Map ban-id -> reasonLabel, for "Resultat" line
                    Map<Long, String> banLabelById = new HashMap<>();
                    for (PunishmentEntry e : bans) banLabelById.put(e.id(), e.reasonLabel());

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        org.bukkit.entity.Player onlineTarget = Bukkit.getPlayer(target.uuid());
                        String targetDisplay;
                        if (onlineTarget != null && !onlineTarget.getDisplayName().isBlank()) {
                            targetDisplay = onlineTarget.getDisplayName();
                        } else if (!cachedDisplay.isBlank()) {
                            targetDisplay = cachedDisplay;
                        } else {
                            targetDisplay = "&8[&f" + targetGroup + "&8] &e" + target.name();
                        }
                        // Live display for any online staff overrides the cached value.
                        for (UUID u : new java.util.ArrayList<>(displayByUuid.keySet())) {
                            org.bukkit.entity.Player on = Bukkit.getPlayer(u);
                            if (on != null && !on.getDisplayName().isBlank()) {
                                displayByUuid.put(u, on.getDisplayName());
                            }
                        }
                        render(sender, target, targetGroup, targetDisplay, rows, groupByUuid, displayByUuid, banLabelById);
                    });
                });
            }
        });
        return true;
    }

    private void render(@NotNull CommandSender sender,
                        @NotNull TargetResolver.Target target,
                        @NotNull String targetGroup,
                        @NotNull String targetDisplay,
                        @NotNull List<Row> rows,
                        @NotNull Map<UUID, String> groupByUuid,
                        @NotNull Map<UUID, String> displayByUuid,
                        @NotNull Map<Long, String> banLabelById) {
        // DKBans-style card header — one line that introduces the player and
        // then every history entry follows as a self-contained card separated
        // by a strike-through line.
        plugin.messages().send(sender, "history-card-header", "display", targetDisplay);

        if (rows.isEmpty()) {
            plugin.messages().send(sender, "lookup-history-empty");
            return;
        }

        for (Row row : rows) sendRow(sender, row, groupByUuid, displayByUuid, banLabelById);
        plugin.messages().send(sender, "history-separator");
    }

    private void sendRow(@NotNull CommandSender sender,
                         @NotNull Row row,
                         @NotNull Map<UUID, String> groupByUuid,
                         @NotNull Map<UUID, String> displayByUuid,
                         @NotNull Map<Long, String> banLabelById) {
        plugin.messages().send(sender, "history-separator");
        // Id colour follows the entry type so a quick scan of the column
        // tells you at a glance what kind of entry you're looking at.
        String idKey = row.isReport() ? "history-card-line-id-report"
                : (row.type() == PunishmentType.BAN
                    ? "history-card-line-id-ban"
                    : "history-card-line-id-mute");
        plugin.messages().send(sender, idKey, "id", row.id());

        String typeKey = row.isReport()
                ? "history-card-line-type-report"
                : (row.type() == PunishmentType.BAN
                    ? "history-card-line-type-ban"
                    : "history-card-line-type-mute");
        plugin.messages().send(sender, typeKey);

        // Active state
        if (row.isActive()) {
            plugin.messages().send(sender, "history-card-line-active-yes");
        } else {
            plugin.messages().send(sender, "history-card-line-active-no",
                    "state", plugin.messages().format(row.stateKey()));
        }

        // Staff / Reporter — clickable. Use the cached/live DisplayName if we
        // have one, otherwise fall back to [group] name.
        sendStaffField(sender,
                row.isReport() ? "history-card-line-reporter-prefix" : "history-card-line-staff-prefix",
                row.staffName(),
                row.staffUuid() == null ? "" : displayByUuid.getOrDefault(row.staffUuid(), ""),
                row.staffUuid() == null ? "" : groupByUuid.getOrDefault(row.staffUuid(), ""));

        plugin.messages().send(sender, "history-card-line-reason", "label", row.label());

        // Duration block: only for bans/mutes
        if (!row.isReport()) {
            plugin.messages().send(sender, "history-card-line-duration",
                    "value", row.durationLabel() == null ? "-" : row.durationLabel());
            plugin.messages().send(sender, "history-card-line-remaining",
                    "value", row.remainingLabel() == null ? "-" : row.remainingLabel());
        }

        plugin.messages().send(sender, "history-card-line-issued",
                "value", DATE.format(row.timestamp()));
        if (row.timeout() != null) {
            plugin.messages().send(sender, "history-card-line-timeout",
                    "value", DATE.format(row.timeout()));
        }

        // Result line: only for reports — points at the ban (if any) that the
        // report led to.
        if (row.isReport()) {
            if (row.banId() != null) {
                String banLabel = banLabelById.getOrDefault(row.banId(), "?");
                plugin.messages().send(sender, "history-card-line-result-ban",
                        "id", row.banId(), "label", banLabel);
            } else {
                plugin.messages().send(sender, "history-card-line-result-none");
            }
        }

        // Modified info — only shown if /modify was used on this entry.
        if (row.modifiedAt() != null) {
            plugin.messages().send(sender, "history-card-line-modified-time",
                    "value", DATE.format(row.modifiedAt()));
            sendStaffField(sender, "history-card-line-modified-by-prefix",
                    row.modifierName() == null ? "?" : row.modifierName(),
                    row.modifierUuid() == null ? "" : displayByUuid.getOrDefault(row.modifierUuid(), ""),
                    row.modifierUuid() == null ? "" : groupByUuid.getOrDefault(row.modifierUuid(), ""));
        }
    }

    /**
     * Sends a one-line prefix from a translation key, then appends a clickable
     * staff name. Uses the {@code displayLegacy} (rank-coloured DisplayName
     * captured at last login) if available; otherwise falls back to the
     * "[Group] Name" shape so non-CloudNet servers still look reasonable.
     */
    private void sendStaffField(@NotNull CommandSender sender, @NotNull String prefixKey,
                                 @NotNull String staffName,
                                 @NotNull String displayLegacy,
                                 @NotNull String staffGroup) {
        String prefix = plugin.messages().format(prefixKey);
        if (sender instanceof Player p) {
            org.bukkit.entity.Player chatPlayer = p;
            net.md_5.bungee.api.chat.BaseComponent nameComp = !displayLegacy.isBlank()
                    ? Components.clickableDisplay(plugin.messages(), staffName, displayLegacy)
                    : Components.clickableStaffWithGroup(plugin.messages(), staffName, staffGroup);
            chatPlayer.spigot().sendMessage(Components.concat(prefix, nameComp));
        } else {
            String suffix;
            if (!displayLegacy.isBlank()) {
                suffix = org.bukkit.ChatColor.translateAlternateColorCodes('&', displayLegacy);
            } else {
                suffix = staffGroup.isEmpty() ? staffName : "[" + staffGroup + "] " + staffName;
            }
            sender.sendMessage(prefix + suffix);
        }
    }

    /* --- Row model ----------------------------------------------------- */

    private record Row(
            boolean isReport,
            @NotNull Instant timestamp,
            PunishmentType type,
            boolean isActive,
            @NotNull String stateKey,
            long id,
            @NotNull String label,
            @NotNull String staffName,
            UUID staffUuid,
            String durationLabel,
            String remainingLabel,
            Instant timeout,
            Long banId,
            Instant modifiedAt,
            UUID modifierUuid,
            String modifierName
    ) {
        static Row fromPunishment(@NotNull PunishmentEntry e) {
            String stateKey = e.active() ? "lookup-state-active"
                    : (e.pardonedAt() != null ? "history-state-pardoned" : "history-state-expired");
            String durationLabel;
            String remainingLabel;
            if (e.isPermanent()) {
                durationLabel = "permanent";
                remainingLabel = e.active() ? "permanent" : "-";
            } else {
                long total = e.expiresAt().getEpochSecond() - e.issuedAt().getEpochSecond();
                durationLabel = DurationParser.formatRemaining(total);
                long left = e.expiresAt().getEpochSecond() - Instant.now().getEpochSecond();
                remainingLabel = (e.active() && left > 0) ? DurationParser.formatRemaining(left) : "-";
            }
            return new Row(false, e.issuedAt(), e.type(), e.active(), stateKey, e.id(),
                    e.reasonLabel(), e.issuerName(), e.issuerUuid(),
                    durationLabel, remainingLabel, e.expiresAt(), null,
                    e.modifiedAt(), e.modifiedByUuid(), e.modifiedByName());
        }

        static Row fromReport(@NotNull ReportEntry r, Long banId) {
            String stateKey = switch (r.status()) {
                case OPEN -> "history-state-report-open";
                case CLAIMED -> "history-state-report-claimed";
                case CLOSED -> "history-state-report-closed";
            };
            boolean active = r.status() != de.eternal.core.model.ReportStatus.CLOSED;
            return new Row(true, r.createdAt(), null, active, stateKey, r.id(),
                    r.reasonLabel(), r.reporterName(), r.reporterUuid(),
                    null, null, null, banId,
                    null, null, null);
        }
    }
}
