package de.eternal.spigot.chatlog;

import de.eternal.core.config.ChatlogConfig;
import de.eternal.core.model.ChatLogEntry;
import de.eternal.core.model.ChatLogKind;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Locale;

/**
 * Feeds public chat and commands into the {@link ChatLogWriter}. Runs at
 * {@code MONITOR} so we record the final, post-mute / post-edit state and never
 * influence the outcome. Sensitive commands (login/register/...) are routed to
 * the separate sensitive queue/table.
 */
public final class ChatLogListener implements Listener {

    private final EternalSpigot plugin;

    public ChatLogListener(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(@NotNull AsyncPlayerChatEvent event) {
        ChatlogConfig cfg = plugin.coreConfig().chatlog();
        if (!cfg.enabled() || !cfg.logChat()) return;
        Player p = event.getPlayer();
        plugin.chatLogWriter().enqueue(new ChatLogEntry(
                0L,
                ChatLogKind.CHAT,
                plugin.serverName(),
                p.getUniqueId().toString(),
                p.getName(),
                null,
                null,
                event.getMessage(),
                Instant.now()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(@NotNull PlayerCommandPreprocessEvent event) {
        ChatlogConfig cfg = plugin.coreConfig().chatlog();
        if (!cfg.enabled() || !cfg.logCommands()) return;

        String message = event.getMessage();
        String first = firstToken(message);

        Player p = event.getPlayer();
        ChatLogEntry entry = new ChatLogEntry(
                0L,
                ChatLogKind.COMMAND,
                plugin.serverName(),
                p.getUniqueId().toString(),
                p.getName(),
                null,
                null,
                message,
                Instant.now()
        );

        if (cfg.sensitiveCommands().contains(first)) {
            plugin.chatLogWriter().enqueueSensitive(entry);
        } else {
            plugin.chatLogWriter().enqueue(entry);
        }
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
