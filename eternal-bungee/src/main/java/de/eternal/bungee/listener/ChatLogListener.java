package de.eternal.bungee.listener;

import de.eternal.bungee.EternalBungee;
import de.eternal.core.chatlog.ChatLogStorage;
import de.eternal.core.config.ChatlogConfig;
import de.eternal.core.model.ChatLogEntry;
import de.eternal.core.model.ChatLogKind;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Records commands that the PROXY handles itself — /ban, /server, /glist and
 * every other Bungee-registered command. These never reach a backend Spigot
 * (the proxy intercepts the command and never forwards it), so the Spigot
 * {@link de.eternal.spigot.chatlog.ChatLogListener PlayerCommandPreprocessEvent
 * logger} never sees them and they were missing from the chat-log entirely.
 *
 * <p>Runs at {@code HIGHEST} so it observes the final cancelled state, and only
 * logs commands whose first token matches a registered proxy command. Anything
 * the proxy does NOT own is forwarded downstream and logged by the backend, so
 * the registered-command guard is what prevents double-logging.</p>
 *
 * <p>Bungee has no batching {@code ChatLogWriter}; proxy command volume is low,
 * so we do a single-row insert straight into {@link ChatLogStorage} on an async
 * thread to keep the SQL round-trip off the Netty thread.</p>
 */
public final class ChatLogListener implements Listener {

    private final EternalBungee plugin;

    public ChatLogListener(@NotNull EternalBungee plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(@NotNull ChatEvent event) {
        if (event.isCancelled() || !event.isCommand()) return;
        if (!(event.getSender() instanceof ProxiedPlayer player)) return;

        ChatlogConfig cfg = plugin.coreConfig().chatlog();
        if (!cfg.enabled() || !cfg.logCommands()) return;
        if (!(plugin.storage() instanceof ChatLogStorage store)) return;

        String message = event.getMessage();
        String first = firstToken(message);

        // Only proxy-handled commands reach a backend-less dead end here; any
        // command the proxy doesn't own is forwarded and logged downstream.
        if (!isProxyCommand(first)) return;

        // Tag with the backend the player is currently on so the command groups
        // with their other activity; fall back to the proxy name pre-connect.
        Server srv = player.getServer();
        String serverName = srv != null ? srv.getInfo().getName() : plugin.coreConfig().serverName();

        ChatLogEntry entry = new ChatLogEntry(
                0L,
                ChatLogKind.COMMAND,
                serverName,
                player.getUniqueId().toString(),
                player.getName(),
                null,
                null,
                message,
                Instant.now()
        );

        boolean sensitive = cfg.sensitiveCommands().contains(first);
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            try {
                if (sensitive) store.appendSensitiveLogs(List.of(entry));
                else store.appendChatLogs(List.of(entry));
            } catch (Exception ex) {
                plugin.getLogger().warning("[Eternal] proxy chat-log insert failed: " + ex.getMessage());
            }
        });
    }

    /** True if the proxy has a command registered under {@code name} — i.e. it
     *  will be handled here and never forwarded to a backend. */
    private boolean isProxyCommand(@NotNull String name) {
        for (var e : ProxyServer.getInstance().getPluginManager().getCommands()) {
            if (e.getKey().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** First command token: strip a single leading '/', lowercase, cut at first space. */
    private static @NotNull String firstToken(@NotNull String message) {
        String s = message;
        if (s.startsWith("/")) s = s.substring(1);
        int sp = s.indexOf(' ');
        if (sp >= 0) s = s.substring(0, sp);
        return s.toLowerCase(Locale.ROOT);
    }
}
