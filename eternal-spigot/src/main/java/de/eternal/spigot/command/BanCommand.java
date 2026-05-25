package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * /ban — share-and-share-alike with /mute. The selected reason's type decides
 * whether the punishment turns into an actual ban or a mute.
 */
public final class BanCommand implements CommandExecutor {

    private final EternalSpigot plugin;
    private final PunishmentDispatch dispatch;

    public BanCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
        this.dispatch = new PunishmentDispatch(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("eternal.ban")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        dispatch.handle(sender, "usage-ban", args);
        return true;
    }
}
