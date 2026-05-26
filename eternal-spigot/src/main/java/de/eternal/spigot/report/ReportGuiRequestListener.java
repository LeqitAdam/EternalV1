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
 * Bridge between the Bungee-side {@code /report <player>} command and the
 * Spigot-side {@link ReportReasonGui}. The Bungee command can't open
 * inventories itself, so when a player types {@code /report Adam} on the
 * proxy, Bungee resolves the target's UUID and sends a plugin-message on
 * this channel to the reporter's current backend; we receive it here and
 * open the GUI on the main thread.
 *
 * <p>Wire format (DataInput):</p>
 * <pre>
 *   UTF subcommand        "open-report-gui"
 *   UTF targetUuid        full 36-char UUID
 *   UTF targetName        display name as resolved by Bungee
 * </pre>
 *
 * <p>The reporter is whichever player the message arrived on — Bukkit
 * plugin messages always carry the receiving player as context, so we
 * don't need to encode it.</p>
 */
public final class ReportGuiRequestListener implements PluginMessageListener {

    public static final String CHANNEL = "eternal:open-report-gui";

    private final EternalSpigot plugin;

    public ReportGuiRequestListener(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
        // Incoming-only on this channel — Bungee sends, Spigot receives.
        // Bukkit requires both directions to be registered for the
        // channel name to be valid in the messenger registry, so we
        // also register an outgoing in case we ever want to send back.
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player reporter,
                                        @NotNull byte[] message) {
        if (!CHANNEL.equals(channel)) return;
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String sub = in.readUTF();
            if (!"open-report-gui".equals(sub)) return;
            String uuidStr = in.readUTF();
            String targetName = in.readUTF();
            UUID targetUuid;
            try { targetUuid = UUID.fromString(uuidStr); }
            catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("ReportGuiRequest: bad UUID '" + uuidStr + "'");
                return;
            }
            // Open the inventory on the main thread — Bukkit refuses
            // inventory operations from the netty/plugin-messaging thread.
            Bukkit.getScheduler().runTask(plugin,
                    () -> plugin.reportReasonGui().open(reporter, targetUuid, targetName));
        } catch (Exception ex) {
            plugin.getLogger().warning("ReportGuiRequest payload invalid: " + ex.getMessage());
        }
    }
}
