package de.eternal.bungee.nick;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import de.eternal.bungee.EternalBungee;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Proxy half of the network-nick protocol on the {@code eternal:nick} channel.
 *
 * <ul>
 *   <li>{@code toggle}/{@code set} from a backend → flip the player's disguise
 *       (the proxy decides + rolls via {@link NickProxyService}).</li>
 *   <li>{@link ServerSwitchEvent} → re-push the active disguise to the new
 *       backend so it survives the switch.</li>
 *   <li>{@link PlayerDisconnectEvent} → clear the session-scoped state.</li>
 * </ul>
 */
public final class NickProxyListener implements Listener {

    public static final String CHANNEL = "eternal:nick";

    private final EternalBungee plugin;
    private final NickProxyService service;

    public NickProxyListener(@NotNull EternalBungee plugin, @NotNull NickProxyService service) {
        this.plugin = plugin;
        this.service = service;
        // Required before incoming messages on the channel are delivered.
        ProxyServer.getInstance().registerChannel(CHANNEL);
    }

    @EventHandler
    public void onPluginMessage(@NotNull PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getTag())) return;
        event.setCancelled(true); // control message — never forward to a client
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
            String sub = in.readUTF();
            UUID uuid = UUID.fromString(in.readUTF());
            ProxiedPlayer p = ProxyServer.getInstance().getPlayer(uuid);
            if (p == null) return;
            switch (sub) {
                case "toggle" -> service.toggle(p);
                case "set" -> service.set(p, in.readBoolean());
                default -> { /* unknown — ignore */ }
            }
        } catch (Exception ex) {
            ProxyServer.getInstance().getLogger().warning("[Eternal] nick message invalid: " + ex.getMessage());
        }
    }

    @EventHandler
    public void onServerSwitch(@NotNull ServerSwitchEvent event) {
        ProxiedPlayer p = event.getPlayer();
        if (!service.isNicked(p.getUniqueId())) return;
        // Small delay so the backend has fully registered the connection (and
        // its eternal:nick channel) before we push the disguise to it.
        ProxyServer.getInstance().getScheduler().schedule(plugin,
                () -> service.reapplyOnSwitch(p), 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @EventHandler
    public void onDisconnect(@NotNull PlayerDisconnectEvent event) {
        service.handleDisconnect(event.getPlayer().getUniqueId());
    }
}
