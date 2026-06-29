package de.eternal.lobby.command;

import de.eternal.lobby.EternalLobby;
import de.eternal.lobby.LobbyItems;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/** Executor for all lobby commands: /lobby, /setlobbyspawn, /navigator, /cosmetics. */
public final class LobbyCommands implements CommandExecutor {

    private final EternalLobby plugin;

    public LobbyCommands(@NotNull EternalLobby plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(LobbyItems.color("&cNur fuer Spieler."));
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "lobby" -> {
                Location spawn = plugin.spawn();
                if (spawn == null) {
                    p.sendMessage(LobbyItems.color("&cKein Lobby-Spawn gesetzt (&7/setlobbyspawn&c)."));
                } else {
                    p.teleport(spawn);
                    p.sendMessage(LobbyItems.color("&aWillkommen in der Lobby."));
                }
            }
            case "setlobbyspawn" -> {
                if (!p.hasPermission("eternal.lobby.admin")) {
                    p.sendMessage(LobbyItems.color("&cKeine Berechtigung."));
                    return true;
                }
                plugin.setSpawn(p.getLocation());
                p.sendMessage(LobbyItems.color("&aLobby-Spawn gesetzt."));
            }
            case "navigator" -> plugin.navigatorGui().open(p);
            case "cosmetics" -> plugin.cosmeticsGui().open(p);
            default -> {
                return false;
            }
        }
        return true;
    }
}
