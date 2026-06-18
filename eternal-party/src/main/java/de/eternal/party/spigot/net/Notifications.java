package de.eternal.party.spigot.net;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import de.eternal.party.spigot.EternalPartySpigot;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Delivers a finished, §-coloured message to a player by UUID — locally if they
 * are on this backend, otherwise routed up to the BungeeCord proxy which finds
 * them on whichever server they are connected to. When there is no proxy
 * (single-server setup) the local path is all that is ever used.
 */
public final class Notifications {

    private final EternalPartySpigot plugin;

    public Notifications(@NotNull EternalPartySpigot plugin) {
        this.plugin = plugin;
    }

    public void notify(@NotNull UUID target, @NotNull String rendered) {
        deliver(target, rendered, "");
    }

    public void notifyClickable(@NotNull UUID target, @NotNull String rendered, @NotNull String runCommand) {
        deliver(target, rendered, runCommand);
    }

    private void deliver(@NotNull UUID target, @NotNull String rendered, @NotNull String clickCommand) {
        Player local = Bukkit.getPlayer(target);
        if (local != null && local.isOnline()) {
            sendLocal(local, rendered, clickCommand);
            return;
        }
        // Cross-server: a plugin message needs an online player as carrier.
        Player carrier = firstOnline();
        if (carrier == null) return; // nobody online here to carry the message
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(PartyChannel.SUB_DELIVER);
        out.writeUTF(target.toString());
        out.writeUTF(rendered);
        out.writeUTF(clickCommand);
        carrier.sendPluginMessage(plugin, PartyChannel.CHANNEL, out.toByteArray());
    }

    private void sendLocal(@NotNull Player p, @NotNull String rendered, @NotNull String clickCommand) {
        if (clickCommand.isEmpty()) {
            for (String line : rendered.split("\\R")) p.sendMessage(line);
            return;
        }
        TextComponent comp = new TextComponent(TextComponent.fromLegacyText(rendered));
        comp.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, clickCommand));
        comp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(TextComponent.fromLegacyText(clickCommand))));
        p.spigot().sendMessage(comp);
    }

    private @Nullable Player firstOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) return p;
        return null;
    }
}
