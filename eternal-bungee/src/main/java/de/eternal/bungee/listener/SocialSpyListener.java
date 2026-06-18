package de.eternal.bungee.listener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import de.eternal.bungee.EternalBungee;
import de.eternal.core.chatlog.ChatLogStorage;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Network-wide social-spy fanout — the proxy mirror of the Spigot
 * {@code /msg} chokepoint. A backend can only see the private messages
 * (and the spy-enabled staff) on its own server; the proxy sits in front
 * of every connection, so it owns the cross-server fanout exactly like
 * {@link StaffBroadcastListener} does for staff broadcasts.
 *
 * <p>The set of spy-enabled UUIDs is mirrored from the DB on startup and
 * kept in memory for O(1) fanout filtering. Two things keep it fresh:</p>
 * <ul>
 *     <li>a {@code toggle} plugin message (sent by the Spigot
 *         {@code /socialspy} command) updates it instantly, and</li>
 *     <li>a 60s resync task reloads it from {@link ChatLogStorage#socialSpyUuids()}
 *         to self-heal against missed toggles (e.g. a proxy restart while a
 *         backend held a different view, or a web-side change).</li>
 * </ul>
 *
 * <p>Wire format (channel {@value #CHANNEL}, DataInput):</p>
 * <pre>
 *   spy:    UTF "spy"    UTF server  UTF sender  UTF target  UTF message
 *   toggle: UTF "toggle" UTF uuid    boolean enabled
 * </pre>
 */
public final class SocialSpyListener implements Listener {

    public static final String CHANNEL = "eternal:socialspy";

    private final EternalBungee plugin;

    /** UUIDs of staff with social-spy enabled. Thread-safe: written by the
     *  resync task and the toggle handler, read by the fanout. */
    private final Set<UUID> spy = ConcurrentHashMap.newKeySet();

    private ScheduledTask resyncTask;

    public SocialSpyListener(@NotNull EternalBungee plugin) {
        this.plugin = plugin;
        // The channel has to be registered on the proxy before incoming
        // messages on it will be delivered to plugins.
        ProxyServer.getInstance().registerChannel(CHANNEL);
        // Initial load off the main thread — the SQL round-trip shouldn't
        // block onEnable.
        ProxyServer.getInstance().getScheduler().runAsync(plugin, this::resync);
    }

    /** Starts the 60s self-healing resync task. */
    public void start() {
        // Async so the DB query stays off the proxy main thread. First run
        // after 60s; the constructor already did the initial load.
        this.resyncTask = ProxyServer.getInstance().getScheduler().schedule(
                plugin, this::resync, 60L, 60L, TimeUnit.SECONDS);
    }

    /** Cancels the resync task; called from {@code onDisable}. */
    public void stop() {
        if (resyncTask != null) { resyncTask.cancel(); resyncTask = null; }
    }

    /** Reloads the in-memory spy set from storage, dropping malformed UUIDs. */
    private void resync() {
        try {
            ChatLogStorage storage = (ChatLogStorage) plugin.storage();
            Set<UUID> fresh = ConcurrentHashMap.newKeySet();
            for (String raw : storage.socialSpyUuids()) {
                try {
                    fresh.add(UUID.fromString(raw));
                } catch (IllegalArgumentException ignored) {
                    // Skip rows that aren't valid UUIDs rather than abort the resync.
                }
            }
            spy.clear();
            spy.addAll(fresh);
        } catch (Exception ex) {
            plugin.getLogger().warning("[Eternal] social-spy resync failed: " + ex.getMessage());
        }
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
            switch (sub) {
                case "spy" -> handleSpy(in);
                case "toggle" -> handleToggle(in);
                default -> { /* unknown sub-command — ignore */ }
            }
        } catch (Exception ex) {
            ProxyServer.getInstance().getLogger().warning(
                    "[Eternal] social-spy message invalid: " + ex.getMessage());
        }
    }

    private void handleSpy(@NotNull ByteArrayDataInput in) {
        String server = in.readUTF();
        String sender = in.readUTF();
        String target = in.readUTF();
        String message = in.readUTF();

        String formatted = plugin.messages().format("socialspy-format",
                "server", server, "sender", sender, "target", target, "message", message);
        // Defensive fallback: BungeeMessages.format returns the key itself
        // when the translation is missing — render a sensible default then.
        if ("socialspy-format".equals(formatted)) {
            formatted = ChatColor.translateAlternateColorCodes('&',
                    "&7[SPY] &f" + sender + " &7→ &f" + target + "&7: &f" + message);
        }

        // Iterate every player on the proxy once and send to the spy-enabled
        // ones — same fanout shape as StaffBroadcastListener.
        for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) {
            if (spy.contains(p.getUniqueId())) {
                p.sendMessage(TextComponent.fromLegacyText(formatted));
            }
        }
    }

    private void handleToggle(@NotNull ByteArrayDataInput in) {
        String uuid = in.readUTF();
        boolean enabled = in.readBoolean();
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (enabled) spy.add(id);
        else spy.remove(id);
    }
}
