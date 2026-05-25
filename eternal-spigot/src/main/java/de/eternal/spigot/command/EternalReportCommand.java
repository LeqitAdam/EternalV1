package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import de.eternal.spigot.report.ReportActions;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Hidden command used by the clickable chat-notification buttons (Annehmen /
 * Ablehnen / TP). Not advertised, not in the usage of /reportsystem — it
 * exists so {@link net.md_5.bungee.api.chat.ClickEvent.Action#RUN_COMMAND}
 * has a target to fire.
 */
public final class EternalReportCommand implements CommandExecutor {

    private final EternalSpigot plugin;
    private final ReportActions actions;

    public EternalReportCommand(@NotNull EternalSpigot plugin, @NotNull ReportActions actions) {
        this.plugin = plugin;
        this.actions = actions;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("eternal.reportsystem")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (!(sender instanceof Player mod)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (args.length < 2) return true;

        long id;
        try { id = Long.parseLong(args[1]); }
        catch (NumberFormatException ex) { return true; }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept", "claim" -> actions.claim(mod, id);
            case "reject", "close" -> actions.close(mod, id, "Abgelehnt durch " + mod.getName());
            case "tp" -> actions.teleportOnly(mod, id);
        }
        return true;
    }
}
