package de.eternal.spigot.command;

import de.eternal.core.model.PlayerProfile;
import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.model.PunishmentType;
import de.eternal.spigot.Components;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

public final class LookupCommand implements CommandExecutor {

    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final EternalSpigot plugin;
    private final TargetResolver resolver;

    public LookupCommand(@NotNull EternalSpigot plugin) {
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
            plugin.messages().send(sender, "usage-lookup");
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
            Optional<PlayerProfile> profile = plugin.storage().findProfile(target.uuid());
            // Tier check intentionally NOT applied here — /lookup and /history
            // are read-only and any staff member with eternal.lookup may use
            // them. Tier only gates ban/unban (see BanCommand / UnbanCommand).
            printOutput(sender, target, profile);
        });
        return true;
    }

    private void printOutput(@NotNull CommandSender sender,
                             @NotNull TargetResolver.Target target,
                             @NotNull Optional<PlayerProfile> profile) {
        Optional<PunishmentEntry> activeBan = plugin.punishments().activeBan(target.uuid());
        Optional<PunishmentEntry> activeMute = plugin.punishments().activeMute(target.uuid());
        List<PunishmentEntry> hist = plugin.punishments().history(target.uuid(), null);

        // Pre-fetch issuer DisplayNames so the mini-history shows real
        // rank-coloured names instead of plain "&eName".
        java.util.Map<java.util.UUID, String> issuerDisplayByUuid = new java.util.HashMap<>();
        int shown = 0;
        for (PunishmentEntry e : hist) {
            if (shown++ >= 5) break;
            if (e.issuerUuid() == null || issuerDisplayByUuid.containsKey(e.issuerUuid())) continue;
            String cached = plugin.storage().findProfile(e.issuerUuid())
                    .map(PlayerProfile::lastDisplayName).orElse("");
            issuerDisplayByUuid.put(e.issuerUuid(), cached);
        }

        String group = profile.map(p -> p.lastGroupName().isEmpty() ? "Spieler" : p.lastGroupName())
                .orElse("Spieler");
        // Cached DisplayName is safe to read async (DB blob). The live
        // online.getDisplayName() lookup MUST happen on the main thread —
        // Paper enforces this and throws IllegalStateException otherwise,
        // which kills our worker silently and produces zero chat output.
        String cachedDisplay = profile.map(PlayerProfile::lastDisplayName).orElse("");

        Bukkit.getScheduler().runTask(plugin, () -> {
            org.bukkit.entity.Player online = Bukkit.getPlayer(target.uuid());
            String displayFinal;
            if (online != null && !online.getDisplayName().isBlank()) {
                displayFinal = online.getDisplayName();
            } else if (!cachedDisplay.isBlank()) {
                displayFinal = cachedDisplay;
            } else {
                displayFinal = group.isEmpty()
                        ? "&e" + target.name()
                        : "&8[&f" + group + "&8] &e" + target.name();
            }
            plugin.messages().send(sender, "lookup-header",
                    "name", target.name(), "uuid", target.uuid(),
                    "group", group, "display", displayFinal);

            profile.ifPresent(p -> {
                plugin.messages().send(sender, "lookup-line",
                        "key", "Erstmals", "value", DATE.format(p.firstSeen()));
                plugin.messages().send(sender, "lookup-line",
                        "key", "Zuletzt", "value", DATE.format(p.lastSeen()));
            });
            plugin.messages().send(sender, "lookup-line",
                    "key", "Online",
                    "value", Bukkit.getPlayer(target.uuid()) != null ? "ja" : "nein");
            plugin.messages().send(sender, "lookup-line",
                    "key", "Aktiver Bann",
                    "value", activeBan.map(b -> "#" + b.id() + " " + b.reasonLabel()
                            + (b.isPermanent() ? " (permanent)" : " (bis " + DATE.format(b.expiresAt()) + ")"))
                            .orElse(plugin.messages().get("none")));
            plugin.messages().send(sender, "lookup-line",
                    "key", "Aktiver Mute",
                    "value", activeMute.map(m -> "#" + m.id() + " " + m.reasonLabel()
                            + (m.isPermanent() ? " (permanent)" : " (bis " + DATE.format(m.expiresAt()) + ")"))
                            .orElse(plugin.messages().get("none")));

            plugin.messages().send(sender, "lookup-history-header");
            if (hist.isEmpty()) {
                plugin.messages().send(sender, "lookup-history-empty");
            } else {
                int shownLine = 0;
                for (PunishmentEntry e : hist) {
                    if (shownLine++ >= 5) break;
                    // Live online display overrides cached, just like target.
                    String issuerDisplay = "";
                    if (e.issuerUuid() != null) {
                        org.bukkit.entity.Player onIssuer = Bukkit.getPlayer(e.issuerUuid());
                        if (onIssuer != null && !onIssuer.getDisplayName().isBlank()) {
                            issuerDisplay = onIssuer.getDisplayName();
                        } else {
                            issuerDisplay = issuerDisplayByUuid.getOrDefault(e.issuerUuid(), "");
                        }
                    }
                    sendHistoryLine(sender, e, issuerDisplay);
                }
            }

            // --- Sessions (letzte 5 Logins inkl. IPs) -------------------
            var sessions = plugin.storage().recentSessions(target.uuid(), 5);
            plugin.messages().send(sender, "lookup-sessions-header");
            if (sessions.isEmpty()) {
                plugin.messages().send(sender, "lookup-sessions-empty");
            } else {
                for (var s : sessions) {
                    if (s.isActive()) {
                        plugin.messages().send(sender, "lookup-sessions-line-active",
                                "ip", s.ip(),
                                "login", DATE.format(s.loginAt()));
                    } else {
                        plugin.messages().send(sender, "lookup-sessions-line",
                                "ip", s.ip(),
                                "login", DATE.format(s.loginAt()),
                                "logout", DATE.format(s.logoutAt()));
                    }
                }
            }
        });
    }

    private void sendHistoryLine(@NotNull CommandSender sender, @NotNull PunishmentEntry e,
                                 @NotNull String issuerDisplay) {
        String stateKey = e.active() ? "lookup-state-active"
                : (e.pardonedAt() != null ? "lookup-state-pardoned" : "lookup-state-expired");
        String stateText = plugin.messages().format(stateKey);

        String lineKey = e.type() == PunishmentType.BAN
                ? "lookup-history-line-ban"
                : "lookup-history-line-mute";
        String template = plugin.messages().format(lineKey,
                "id", e.id(),
                "label", e.reasonLabel(),
                "issuer", " ISSUER ",
                "state", stateText);

        if (sender instanceof Player p) {
            String[] parts = template.split(" ISSUER ", 2);
            String before = parts[0];
            String after = parts.length > 1 ? parts[1] : "";
            var nameComp = issuerDisplay.isBlank()
                    ? Components.clickableStaff(plugin.messages(), e.issuerName())
                    : Components.clickableDisplay(plugin.messages(), e.issuerName(), issuerDisplay);
            p.spigot().sendMessage(Components.concat(before, nameComp, after));
        } else {
            // Console — no clicks, just print the legacy string.
            String inline = issuerDisplay.isBlank()
                    ? e.issuerName()
                    : org.bukkit.ChatColor.translateAlternateColorCodes('&', issuerDisplay);
            sender.sendMessage(template.replace(" ISSUER ", inline));
        }
    }
}
