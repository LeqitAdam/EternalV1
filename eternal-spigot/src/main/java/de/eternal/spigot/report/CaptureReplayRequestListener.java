package de.eternal.spigot.report;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Bridge between the Bungee-side {@code /report} command and the
 * Spigot-side replay capture. Bungee creates the report row in the DB
 * itself, but it has no recorder of its own — the in-flight replay
 * capture has to run on whichever backend the reportee is currently on,
 * so Bungee fires a plugin-message on this channel to that backend, and
 * we call into {@code ReplayBridge.captureForReport} here.
 *
 * <p>Wire format (DataInput):</p>
 * <pre>
 *   UTF subcommand        "start-capture"
 *   UTF targetUuid        full 36-char UUID
 *   long reportId
 * </pre>
 *
 * <p>The plugin-message arrives on a carrier player (any online player —
 * Bungee picks the reportee themselves when possible, falling back to any
 * online player on the matching backend). The carrier isn't relevant for
 * the actual capture call.</p>
 */
public final class CaptureReplayRequestListener implements PluginMessageListener {

    public static final String CHANNEL = "eternal:start-replay-capture";

    private final EternalSpigot plugin;

    public CaptureReplayRequestListener(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player carrier,
                                        @NotNull byte[] message) {
        if (!CHANNEL.equals(channel)) return;
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String sub = in.readUTF();
            if (!"start-capture".equals(sub)) return;
            String uuidStr = in.readUTF();
            long reportId = in.readLong();
            UUID targetUuid;
            try { targetUuid = UUID.fromString(uuidStr); }
            catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("CaptureReplayRequest: bad UUID '" + uuidStr + "'");
                return;
            }
            // captureForReport is thread-safe (snapshots the recorder
            // buffers + schedules a timeout) but the ReplayApi prefers
            // main-thread interaction for the underlying Bukkit
            // scheduler call inside captureWindow.
            Bukkit.getScheduler().runTask(plugin, () ->
                    plugin.replayBridge().captureForReport(targetUuid, reportId));
        } catch (Exception ex) {
            plugin.getLogger().warning("CaptureReplayRequest payload invalid: " + ex.getMessage());
        }
    }
}
