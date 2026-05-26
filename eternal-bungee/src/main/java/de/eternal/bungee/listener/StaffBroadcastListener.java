package de.eternal.bungee.listener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import de.eternal.bungee.EternalBungee;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import org.jetbrains.annotations.NotNull;

/**
 * Bridges in-game staff broadcasts (e.g. "Adam was banned by Mod #X") from
 * the Spigot-side action handler to a cross-server fanout via the proxy.
 *
 * <p>Spigot can broadcast to its own online players cheaply, but the
 * staff online on OTHER backends would miss it. Bungee sits in front of
 * every connection, so it can iterate every proxied player exactly once
 * and pick the ones with the {@code eternal.notify}/{@code .ban}/{@code .mute}
 * permission — same filter the regular {@code /ban} broadcast uses.</p>
 *
 * <p>Wire format (DataInput):</p>
 * <pre>
 *   UTF subcommand    "staff-notify"
 *   UTF message       Already &amp;-coded; we just translateAlternateColorCodes here
 * </pre>
 */
public final class StaffBroadcastListener implements Listener {

    public static final String CHANNEL = "eternal:staff-broadcast";

    @SuppressWarnings("unused")
    private final EternalBungee plugin;

    public StaffBroadcastListener(@NotNull EternalBungee plugin) {
        this.plugin = plugin;
        // The channel has to be registered on the proxy before incoming
        // messages on it will be delivered to plugins.
        ProxyServer.getInstance().registerChannel(CHANNEL);
    }

    @EventHandler
    public void onPluginMessage(@NotNull PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getTag())) return;
        // Mark the message as handled so it doesn't get forwarded to the
        // client — these are control messages, not chat.
        event.setCancelled(true);
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
            String sub = in.readUTF();
            if (!"staff-notify".equals(sub)) return;
            String raw = in.readUTF();
            String formatted = ChatColor.translateAlternateColorCodes('&', raw);
            // Iterate every player on the proxy and filter by the same
            // permissions the in-game /ban broadcast uses. Bungee's own
            // permission lookup includes everything CloudPerms feeds in.
            for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) {
                if (p.hasPermission("eternal.notify")
                        || p.hasPermission("eternal.ban")
                        || p.hasPermission("eternal.mute")) {
                    p.sendMessage(TextComponent.fromLegacyText(formatted));
                }
            }
            ProxyServer.getInstance().getLogger().info(ChatColor.stripColor(formatted));
        } catch (Exception ex) {
            ProxyServer.getInstance().getLogger().warning(
                    "[Eternal] staff-broadcast message invalid: " + ex.getMessage());
        }
    }
}
