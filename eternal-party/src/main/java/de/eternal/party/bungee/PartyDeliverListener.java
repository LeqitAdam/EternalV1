package de.eternal.party.bungee;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.api.event.PluginMessageEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Receives {@code eternal:party} "deliver" messages from a backend and forwards
 * the finished message to the addressed player on whichever server they are.
 * Wire: {@code writeUTF("deliver"); writeUTF(uuid); writeUTF(text); writeUTF(click)}.
 */
public final class PartyDeliverListener implements Listener {

    private final EternalPartyBungee plugin;

    public PartyDeliverListener(@NotNull EternalPartyBungee plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMessage(@NotNull PluginMessageEvent event) {
        if (!EternalPartyBungee.CHANNEL.equals(event.getTag())) return;
        // Only trust messages coming from a backend server, never from a client.
        if (!(event.getSender() instanceof Server)) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true); // internal channel — don't pass it on

        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String sub = in.readUTF();
        if (!"deliver".equals(sub)) return;

        String uuidStr = in.readUTF();
        String text = in.readUTF();
        String click = in.readUTF();

        UUID target;
        try {
            target = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException ex) {
            return;
        }
        ProxiedPlayer player = plugin.getProxy().getPlayer(target);
        if (player == null) return; // not online anywhere on the network

        BaseComponent[] body = TextComponent.fromLegacyText(text);
        TextComponent component = new TextComponent(body);
        if (!click.isEmpty()) {
            component.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, click));
            component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text(TextComponent.fromLegacyText(click))));
        }
        player.sendMessage(component);
    }
}
