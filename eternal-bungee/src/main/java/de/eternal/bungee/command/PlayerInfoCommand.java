package de.eternal.bungee.command;

import de.eternal.bungee.EternalBungee;
import de.eternal.core.model.PlayerProfile;
import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.social.SocialStorage;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import net.md_5.bungee.api.plugin.Command;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * {@code /playerinfo} — a compact DKBans-style info card: rank, name, UUID, IP,
 * session, nick status, plus a clickable button that opens the (potentially long)
 * full {@code /history}. Splitting the long history off the card keeps the info
 * readable — important since a nicked player's resolved history can be huge.
 */
public final class PlayerInfoCommand extends Command implements net.md_5.bungee.api.plugin.TabExecutor {

    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final String BAR = "&8&m                                                  ";

    private final EternalBungee plugin;
    private final TargetResolver resolver;

    public PlayerInfoCommand(@NotNull EternalBungee plugin) {
        super("playerinfo", "eternal.lookup", "pinfo", "whois");
        this.plugin = plugin;
        this.resolver = new TargetResolver(plugin.storage());
    }

    @Override
    public void execute(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 1) {
            sender.sendMessage(TextComponent.fromLegacyText(color("&cBenutzung&8: &7/playerinfo <Spieler>")));
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
            var sessions = plugin.storage().recentSessions(target.uuid(), 1);
            Optional<de.eternal.core.model.NickSession> nick =
                    ((SocialStorage) plugin.storage()).findNickSession(target.uuid());

            String group = profile.map(p -> p.lastGroupName().isEmpty() ? "Spieler" : p.lastGroupName())
                    .orElse("Spieler");
            String groupColor = plugin.cloudPerms().groupColor(group);
            if (groupColor.isEmpty()) groupColor = "&f";
            String display = profile.map(PlayerProfile::lastDisplayName).filter(s -> !s.isBlank())
                    .orElse("&7" + target.name());
            boolean online = ProxyServer.getInstance().getPlayer(target.uuid()) != null;
            String ip = profile.map(PlayerProfile::lastAddress).filter(s -> !s.isBlank()).orElse("?");

            line(sender, BAR);
            line(sender, "&bSpieler-Info &8» " + display);
            if (nick.isPresent()) {
                String nickGroup = nick.get().nickGroup();
                String nickColor = nickGroup.isEmpty() ? "&d" : plugin.cloudPerms().groupColor(nickGroup);
                if (nickColor.isEmpty()) nickColor = "&d";
                String rankPart = nickGroup.isEmpty() ? "" : " &8(" + nickColor + nickGroup + "&8)";
                line(sender, " &7Genickt als&8: " + nickColor + nick.get().nickName() + rankPart);
            }
            line(sender, " &7Name&8: &f" + target.name());
            line(sender, " &7Rang&8: " + groupColor + group);
            line(sender, " &7UUID&8: &f" + target.uuid());
            line(sender, " &7IP&8: &f" + ip);
            line(sender, " &7Online&8: " + (online ? "&aja" : "&cnein"));
            profile.ifPresent(p -> {
                line(sender, " &7Erstmals&8: &f" + DATE.format(p.firstSeen())
                        + "  &7Zuletzt&8: &f" + DATE.format(p.lastSeen()));
            });
            if (!sessions.isEmpty()) {
                var s = sessions.get(0);
                line(sender, " &7Letzte Session&8: &f" + s.ip() + " &8(" + DATE.format(s.loginAt()) + ")");
            }
            line(sender, " &7Aktiver Bann&8: " + activeBan
                    .map(b -> "&c#" + b.id() + " " + b.reasonLabel()).orElse("&a—"));
            line(sender, " &7Aktiver Mute&8: " + activeMute
                    .map(m -> "&6#" + m.id() + " " + m.reasonLabel()).orElse("&a—"));

            // Interactive button → opens the full history.
            TextComponent btn = new TextComponent(TextComponent.fromLegacyText(
                    color(" &8[ &e&l» History anzeigen &8]")));
            btn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/history " + target.name()));
            btn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text(TextComponent.fromLegacyText(color("&7Klicke für die volle History von &f" + target.name())))));
            sender.sendMessage(btn);
            line(sender, BAR);
        });
    }

    private void line(@NotNull CommandSender to, @NotNull String legacy) {
        to.sendMessage(TextComponent.fromLegacyText(color(legacy)));
    }

    private static String color(@NotNull String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
    @Override
    public Iterable<String> onTabComplete(net.md_5.bungee.api.CommandSender sender, String[] args) {
        return BungeeTab.players(plugin, args);
    }
}
