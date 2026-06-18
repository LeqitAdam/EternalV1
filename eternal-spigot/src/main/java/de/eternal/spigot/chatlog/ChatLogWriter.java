package de.eternal.spigot.chatlog;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import de.eternal.core.chatlog.ChatLogStorage;
import de.eternal.core.config.ChatlogConfig;
import de.eternal.core.model.ChatLogEntry;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Async batched writer for the chat-log feature. Two thread-safe queues
 * (normal + sensitive) are drained on a Bukkit async timer (period =
 * {@code chatlog.flushIntervalTicks}) into:
 * <ul>
 *     <li>the {@link ChatLogStorage} DB tables
 *         ({@link ChatLogStorage#appendChatLogs}/{@link ChatLogStorage#appendSensitiveLogs}), and</li>
 *     <li>per-server flat files under {@code <chatlog.directory>/<server>/}.</li>
 * </ul>
 *
 * <p>Everything is a no-op when {@code chatlog.enabled()} is false. All file IO
 * is guarded so a broken filesystem never takes the writer (or the flush task)
 * down.</p>
 *
 * <p>Also doubles as the outgoing-side helper for the {@code eternal:socialspy}
 * plugin-message channel (toggle + spy fanout) — see {@link #sendSpy} and
 * {@link #sendToggle}; the proxy fans those out network-wide.</p>
 */
public final class ChatLogWriter {

    /** Lowercase plugin-message channel — mirrors {@code eternal:staff-broadcast}. */
    public static final String SOCIALSPY_CHANNEL = "eternal:socialspy";

    private static final DateTimeFormatter FILE_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter LINE_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final EternalSpigot plugin;
    private final ChatLogStorage storage;
    private final ChatlogConfig config;
    private final Path directory;

    private final ConcurrentLinkedQueue<ChatLogEntry> normal = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ChatLogEntry> sensitive = new ConcurrentLinkedQueue<>();

    private BukkitTask task;

    public ChatLogWriter(@NotNull EternalSpigot plugin,
                         @NotNull ChatLogStorage storage,
                         @NotNull ChatlogConfig config) {
        this.plugin = plugin;
        this.storage = storage;
        this.config = config;
        this.directory = config.directory();
    }

    /** Schedules the async flush task. No-op (and no scheduling) when disabled. */
    public void start() {
        if (!config.enabled()) {
            plugin.getLogger().info("ChatLogWriter: chatlog.enabled=false — kein Logging.");
            return;
        }
        // Lazy-register the outgoing socialspy channel up-front so the first
        // spy/toggle send doesn't race the registration (the bridge methods
        // also guard, this is just belt-and-braces).
        if (!plugin.getServer().getMessenger().isOutgoingChannelRegistered(plugin, SOCIALSPY_CHANNEL)) {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, SOCIALSPY_CHANNEL);
        }
        long period = Math.max(1L, config.flushIntervalTicks());
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flush, period, period);
        plugin.getLogger().info("ChatLogWriter aktiv (flush alle " + period + " Ticks, dir=" + directory + ").");
    }

    /** Cancels the timer and drains whatever is left. Called on disable. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        flushNow();
    }

    public void enqueue(@NotNull ChatLogEntry entry) {
        if (!config.enabled()) return;
        normal.add(entry);
    }

    public void enqueueSensitive(@NotNull ChatLogEntry entry) {
        if (!config.enabled()) return;
        sensitive.add(entry);
    }

    /** Synchronous drain — used on disable so nothing is lost. */
    public void flushNow() {
        flush();
    }

    /* ----------------------------------------------------------------- */

    private void flush() {
        List<ChatLogEntry> normalBatch = drain(normal);
        List<ChatLogEntry> sensitiveBatch = drain(sensitive);
        if (normalBatch.isEmpty() && sensitiveBatch.isEmpty()) return;

        if (!normalBatch.isEmpty()) {
            try {
                storage.appendChatLogs(normalBatch);
            } catch (Exception ex) {
                plugin.getLogger().warning("ChatLogWriter: appendChatLogs fehlgeschlagen: " + ex.getMessage());
            }
            writeFiles(normalBatch, false);
        }
        if (!sensitiveBatch.isEmpty()) {
            try {
                storage.appendSensitiveLogs(sensitiveBatch);
            } catch (Exception ex) {
                plugin.getLogger().warning("ChatLogWriter: appendSensitiveLogs fehlgeschlagen: " + ex.getMessage());
            }
            writeFiles(sensitiveBatch, true);
        }
    }

    private static @NotNull List<ChatLogEntry> drain(@NotNull ConcurrentLinkedQueue<ChatLogEntry> q) {
        List<ChatLogEntry> out = new ArrayList<>();
        ChatLogEntry e;
        while ((e = q.poll()) != null) out.add(e);
        return out;
    }

    /**
     * Appends each entry to {@code <directory>/<server>/<date>.log} (normal) or
     * {@code <directory>/<server>/sensitive-<date>.log} (sensitive). Grouped per
     * (server, date) so a batch spanning midnight still lands correctly.
     */
    private void writeFiles(@NotNull List<ChatLogEntry> batch, boolean sensitiveFile) {
        StringBuilder sb = new StringBuilder();
        // Batches are tiny in practice (one flush window); a single grouped
        // file per cycle is fine because all entries share this.server. We
        // still bucket by date so a flush straddling midnight is correct.
        String currentDate = null;
        for (ChatLogEntry entry : batch) {
            String date = FILE_DATE.format(entry.createdAt());
            if (currentDate == null) currentDate = date;
            if (!date.equals(currentDate)) {
                appendTo(entry.server(), currentDate, sensitiveFile, sb.toString());
                sb.setLength(0);
                currentDate = date;
            }
            sb.append(formatLine(entry)).append('\n');
        }
        if (sb.length() > 0 && currentDate != null) {
            appendTo(batch.get(batch.size() - 1).server(), currentDate, sensitiveFile, sb.toString());
        }
    }

    private void appendTo(@NotNull String serverName, @NotNull String date, boolean sensitiveFile, @NotNull String text) {
        if (text.isEmpty()) return;
        try {
            Path serverDir = directory.resolve(serverName);
            Files.createDirectories(serverDir);
            String fileName = (sensitiveFile ? "sensitive-" : "") + date + ".log";
            Path file = serverDir.resolve(fileName);
            Files.writeString(file, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            plugin.getLogger().warning("ChatLogWriter: Datei-IO fehlgeschlagen (" + date + "): " + ex.getMessage());
        }
    }

    private static @NotNull String formatLine(@NotNull ChatLogEntry e) {
        String time = LINE_TIME.format(e.createdAt());
        return switch (e.kind()) {
            case CHAT -> "[" + time + "] [CHAT] " + e.senderName() + ": " + e.content();
            case MSG -> "[" + time + "] [MSG] " + e.senderName() + " -> " + e.targetName() + ": " + e.content();
            case COMMAND -> "[" + time + "] [CMD] " + e.senderName() + ": " + e.content();
        };
    }

    /* ----------------------------------------------------------------- */
    /* Outgoing eternal:socialspy plugin-message helpers                 */
    /* ----------------------------------------------------------------- */

    /**
     * Network-wide spy fanout for a private message. Sends
     * {@code writeUTF("spy"); writeUTF(server); writeUTF(sender); writeUTF(target); writeUTF(message)}
     * on {@link #SOCIALSPY_CHANNEL} using {@code carrier} as the transport
     * player. The proxy fans it out to every spying staff member — we do NOT
     * deliver locally. No-op when the carrier is null/offline.
     */
    public void sendSpy(@Nullable Player carrier,
                        @NotNull String serverName,
                        @NotNull String senderName,
                        @NotNull String targetName,
                        @NotNull String message) {
        if (carrier == null || !carrier.isOnline()) return;
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("spy");
        out.writeUTF(serverName);
        out.writeUTF(senderName);
        out.writeUTF(targetName);
        out.writeUTF(message);
        send(carrier, out);
    }

    /**
     * Social-spy toggle propagation. Sends
     * {@code writeUTF("toggle"); writeUTF(uuid); writeBoolean(enabled)} on
     * {@link #SOCIALSPY_CHANNEL} so the proxy updates its in-memory spy set.
     * No-op when the carrier is null/offline.
     */
    public void sendToggle(@Nullable Player carrier, @NotNull String uuid, boolean enabled) {
        if (carrier == null || !carrier.isOnline()) return;
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("toggle");
        out.writeUTF(uuid);
        out.writeBoolean(enabled);
        send(carrier, out);
    }

    private void send(@NotNull Player carrier, @NotNull ByteArrayDataOutput out) {
        // Lazy register the outgoing channel, mirroring ActionPoller#broadcast.
        if (!plugin.getServer().getMessenger().isOutgoingChannelRegistered(plugin, SOCIALSPY_CHANNEL)) {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, SOCIALSPY_CHANNEL);
        }
        carrier.sendPluginMessage(plugin, SOCIALSPY_CHANNEL, out.toByteArray());
    }
}
