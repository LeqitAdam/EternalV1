package de.eternal.bungee.command;

import de.eternal.bungee.EternalBungee;
import de.eternal.core.model.PunishmentReason;
import de.eternal.core.model.PunishmentType;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;
import org.jetbrains.annotations.NotNull;

public final class BanCommand extends Command implements net.md_5.bungee.api.plugin.TabExecutor {

    private final EternalBungee plugin;
    private final PunishmentDispatch dispatch;

    public BanCommand(@NotNull EternalBungee plugin) {
        super("ban", "eternal.ban");
        this.plugin = plugin;
        this.dispatch = new PunishmentDispatch(plugin);
    }

    @Override
    public void execute(@NotNull CommandSender sender, @NotNull String[] args) {
        dispatch.handle(sender, "usage-ban", args);
    }
    @Override
    public Iterable<String> onTabComplete(net.md_5.bungee.api.CommandSender sender, String[] args) {
        return BungeeTab.players(plugin, args);
    }
}
