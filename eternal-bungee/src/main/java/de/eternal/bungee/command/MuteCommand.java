package de.eternal.bungee.command;

import de.eternal.bungee.EternalBungee;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;
import org.jetbrains.annotations.NotNull;

public final class MuteCommand extends Command {

    private final EternalBungee plugin;
    private final PunishmentDispatch dispatch;

    public MuteCommand(@NotNull EternalBungee plugin) {
        super("mute", "eternal.mute");
        this.plugin = plugin;
        this.dispatch = new PunishmentDispatch(plugin);
    }

    @Override
    public void execute(@NotNull CommandSender sender, @NotNull String[] args) {
        dispatch.handle(sender, "usage-mute", args);
    }
}
