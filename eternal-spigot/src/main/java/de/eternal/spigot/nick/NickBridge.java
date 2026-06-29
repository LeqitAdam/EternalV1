package de.eternal.spigot.nick;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Backend half of the network-nick protocol on the {@code eternal:nick}
 * plugin-message channel.
 *
 * <ul>
 *   <li><b>outgoing</b> ({@code toggle}/{@code set}): a backend trigger (item or
 *       command) asks the proxy to flip the player's disguise. The proxy owns
 *       the decision + roll.</li>
 *   <li><b>incoming</b> ({@code apply}/{@code clear}): the proxy tells this
 *       backend to put the disguise on / take it off — on first connect and
 *       again after every server switch.</li>
 * </ul>
 *
 * <p>Plugin messages are delivered on the main server thread, so the
 * {@link NickService} calls made here are main-thread safe.</p>
 */
public final class NickBridge implements PluginMessageListener {

    public static final String CHANNEL = "eternal:nick";

    private final EternalSpigot plugin;

    public NickBridge(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
        var messenger = plugin.getServer().getMessenger();
        if (!messenger.isOutgoingChannelRegistered(plugin, CHANNEL)) {
            messenger.registerOutgoingPluginChannel(plugin, CHANNEL);
        }
        if (!messenger.isIncomingChannelRegistered(plugin, CHANNEL)) {
            messenger.registerIncomingPluginChannel(plugin, CHANNEL, this);
        }
    }

    /** Ask the proxy to toggle this player's nick. */
    public void requestToggle(@NotNull Player p) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("toggle");
        out.writeUTF(p.getUniqueId().toString());
        p.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
    }

    /** Ask the proxy to set this player's nick to a specific state. */
    public void requestSet(@NotNull Player p, boolean on) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("set");
        out.writeUTF(p.getUniqueId().toString());
        out.writeBoolean(on);
        p.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String sub = in.readUTF();
            switch (sub) {
                case "apply" -> {
                    UUID uuid = UUID.fromString(in.readUTF());
                    String fakeName = in.readUTF();
                    String skinValue = emptyToNull(in.readUTF());
                    String skinSig = emptyToNull(in.readUTF());
                    String prefix = in.readUTF();
                    String suffix = in.readUTF();
                    String nickGroup = in.readUTF();
                    Player target = Bukkit.getPlayer(uuid);
                    if (target != null) {
                        plugin.nickService().applyDisguise(target, fakeName, skinValue, skinSig, prefix, suffix, nickGroup);
                    }
                }
                case "clear" -> {
                    UUID uuid = UUID.fromString(in.readUTF());
                    String realName = in.readUTF();
                    String skinValue = emptyToNull(in.readUTF());
                    String skinSig = emptyToNull(in.readUTF());
                    Player target = Bukkit.getPlayer(uuid);
                    if (target != null) {
                        plugin.nickService().restore(target, realName, skinValue, skinSig);
                    }
                }
                default -> { /* unknown sub-channel — ignore */ }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[Eternal] nick message invalid: " + ex.getMessage());
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
