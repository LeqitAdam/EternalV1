package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/** /mute — synonym to /ban (same reason list, same dispatch). */
public final class MuteCommand implements CommandExecutor {

    private final EternalSpigot plugin;
    private final PunishmentDispatch dispatch;

    public MuteCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
        this.dispatch = new PunishmentDispatch(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("eternal.mute")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        dispatch.handle(sender, "usage-mute", args);
        return true;
    }
}
