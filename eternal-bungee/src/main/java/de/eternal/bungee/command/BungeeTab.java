package de.eternal.bungee.command;

import de.eternal.bungee.EternalBungee;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared tab-completion: online player names for the first argument, using the
 *  FAKE name for nicked players (so staff tab what they see + it resolves). */
final class BungeeTab {

    private BungeeTab() {
    }

    static @NotNull Iterable<String> players(@NotNull EternalBungee plugin, @NotNull String[] args) {
        if (args.length > 1) return List.of(); // only the first arg is a player
        String prefix = (args.length == 0 ? "" : args[0]).toLowerCase(Locale.ROOT);
        var nick = plugin.nickProxy(); // in-memory, no DB on each keystroke
        List<String> out = new ArrayList<>();
        for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers()) {
            String fake = nick == null ? null : nick.nickNameOf(p.getUniqueId());
            String name = fake != null ? fake : p.getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(name);
        }
        return out;
    }
}
