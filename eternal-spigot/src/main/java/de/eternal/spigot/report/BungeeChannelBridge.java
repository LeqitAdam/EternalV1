package de.eternal.spigot.report;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Tiny wrapper around the Bukkit-side "BungeeCord" plugin-messaging channel.
 *
 * Supports the two operations we need:
 *  - GetPlayerServer  — ask Bungee on which backend a player currently is
 *  - Connect          — send a player to another backend
 *
 * Bungee responses are async, so callers hand in a callback that we fire
 * once the matching reply arrives on the channel.
 */
public final class BungeeChannelBridge implements PluginMessageListener {

    private static final String CHANNEL = "BungeeCord";

    private final EternalSpigot plugin;
    private final ConcurrentHashMap<String, Consumer<String>> pending = new ConcurrentHashMap<>();

    public BungeeChannelBridge(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
    }

    public void askPlayerServer(@NotNull Player carrier, @NotNull String targetName,
                                @NotNull Consumer<String> callback) {
        pending.put(targetName.toLowerCase(), callback);
        // Bungee replies on the same channel after a "GetPlayerServer" request,
        // but the reply uses the carrier's session — so any online player can
        // act as the carrier. We use the mod themselves.
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("GetPlayerServer");
        out.writeUTF(targetName);
        carrier.sendPluginMessage(plugin, CHANNEL, out.toByteArray());

        // Safety timeout — if Bungee never answers, fall back to "offline".
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Consumer<String> cb = pending.remove(targetName.toLowerCase());
            if (cb != null) cb.accept(null);
        }, 60L); // 3 seconds
    }

    public void connect(@NotNull Player who, @NotNull String server) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(server);
        who.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player,
                                        @NotNull byte[] message) {
        if (!channel.equals(CHANNEL)) return;
        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String sub = in.readUTF();
        switch (sub) {
            case "GetPlayerServer" -> {
                String name = in.readUTF();
                String server = in.readUTF();
                Consumer<String> cb = pending.remove(name.toLowerCase());
                if (cb != null) cb.accept(server.isEmpty() ? null : server);
            }
            default -> {
                // we only listen for GetPlayerServer responses; ignore everything else
            }
        }
    }

    @SuppressWarnings("unused")
    private static @Nullable String uuidStr(@Nullable UUID u) {
        return u == null ? null : u.toString();
    }
}
