package de.eternal.bungee.command;

import de.eternal.bungee.EternalBungee;
import de.eternal.core.model.PlayerProfile;
import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.model.PunishmentType;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bungee-side {@code /lookup} — same output as the old Spigot version but now
 * authoritative across the network. Reads everything from the shared SQL
 * storage so a single proxy restart picks up translation changes for the
 * whole cluster.
 */
public final class LookupCommand extends Command implements net.md_5.bungee.api.plugin.TabExecutor {

    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final EternalBungee plugin;
    private final TargetResolver resolver;

    public LookupCommand(@NotNull EternalBungee plugin) {
        super("lookup", "eternal.lookup");
        this.plugin = plugin;
        this.resolver = new TargetResolver(plugin.storage());
    }

    @Override
    public void execute(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 1) {
            plugin.messages().send(sender, "usage-lookup");
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
            Optional<PlayerProfile> profile = plugin.storage().findProfile(target.uuid());
            Optional<PunishmentEntry> activeBan = plugin.punishments().activeBan(target.uuid());
            Optional<PunishmentEntry> activeMute = plugin.punishments().activeMute(target.uuid());
            List<PunishmentEntry> hist = plugin.punishments().history(target.uuid(), null);

            // Pre-fetch issuer DisplayNames once per UUID — same trick as
            // Spigot, just async on Bungee's worker pool.
            Map<UUID, String> issuerDisplayByUuid = new HashMap<>();
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
            // Live ProxiedPlayer DisplayName isn't formatted by chat-plugins on
            // Bungee (they run on Spigot) — so the cached value from the last
            // Spigot-side join is our best bet here, with the [Group] Name
            // synthetic fallback for never-seen offline players.
            String cachedDisplay = profile.map(PlayerProfile::lastDisplayName).orElse("");
            String displayFinal = !cachedDisplay.isBlank()
                    ? cachedDisplay
                    : (group.isEmpty()
                            ? "&e" + target.name()
                            : "&8[&f" + group + "&8] &e" + target.name());

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
                    "value", ProxyServer.getInstance().getPlayer(target.uuid()) != null ? "ja" : "nein");
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

            // Nick-Status: nur anzeigen wenn der Spieler gerade genickt ist —
            // damit Team checken kann, wer sich hinter welchem Fake-Namen versteckt.
            ((de.eternal.core.social.SocialStorage) plugin.storage()).findNickSession(target.uuid())
                    .ifPresent(ns -> plugin.messages().send(sender, "lookup-line",
                            "key", "Genickt als", "value", "&d" + ns.nickName()));

            plugin.messages().send(sender, "lookup-history-header");
            if (hist.isEmpty()) {
                plugin.messages().send(sender, "lookup-history-empty");
            } else {
                int line = 0;
                for (PunishmentEntry e : hist) {
                    if (line++ >= 5) break;
                    String issuerDisplay = e.issuerUuid() == null
                            ? ""
                            : issuerDisplayByUuid.getOrDefault(e.issuerUuid(), "");
                    sendHistoryLine(sender, e, issuerDisplay);
                }
            }

            // Sessions
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
        String[] parts = template.split(" ISSUER ", 2);
        String before = parts[0];
        String after = parts.length > 1 ? parts[1] : "";

        List<BaseComponent> out = new ArrayList<>();
        for (BaseComponent c : TextComponent.fromLegacyText(before)) out.add(c);
        out.add(clickableName(e.issuerName(), issuerDisplay));
        for (BaseComponent c : TextComponent.fromLegacyText(after)) out.add(c);
        sender.sendMessage(out.toArray(new BaseComponent[0]));
    }

    /** Renders the rank-coloured cached DisplayName (or {@code &e<name>}) as a
     *  click-to-lookup component. */
    private BaseComponent clickableName(@NotNull String fallbackName, @NotNull String displayLegacy) {
        String legacy = displayLegacy.isBlank() ? "&e" + fallbackName : displayLegacy;
        TextComponent c = new TextComponent(TextComponent.fromLegacyText(
                ChatColor.translateAlternateColorCodes('&', legacy)));
        c.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lookup " + fallbackName));
        c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(TextComponent.fromLegacyText(plugin.messages().format(
                        "hover-show-staff", "name", fallbackName)))));
        return c;
    }

    /** Shared by HistoryCommand. */
    static BaseComponent clickableNameStatic(@NotNull EternalBungee plugin,
                                              @NotNull String fallbackName,
                                              @NotNull String displayLegacy) {
        String legacy = displayLegacy.isBlank() ? "&e" + fallbackName : displayLegacy;
        TextComponent c = new TextComponent(TextComponent.fromLegacyText(
                ChatColor.translateAlternateColorCodes('&', legacy)));
        c.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lookup " + fallbackName));
        c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(TextComponent.fromLegacyText(plugin.messages().format(
                        "hover-show-staff", "name", fallbackName)))));
        return c;
    }
    @Override
    public Iterable<String> onTabComplete(net.md_5.bungee.api.CommandSender sender, String[] args) {
        return BungeeTab.players(plugin, args);
    }
}
