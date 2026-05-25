package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * /reportsystem — drastisch reduziert: nur login/logout/list.
 * claim/close/tp passieren ueber die {@link de.eternal.spigot.report.ReportListGui}
 * oder die Chat-Klick-Buttons; sie sollen nicht von Hand getippt werden.
 */
public final class ReportSystemCommand implements CommandExecutor {

    private final EternalSpigot plugin;

    public ReportSystemCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
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
        if (args.length < 1) {
            plugin.messages().send(sender, "usage-reportsystem");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "login" -> {
                if (plugin.staff().isLoggedIn(mod.getUniqueId())) {
                    plugin.messages().send(sender, "reportsystem-already-in");
                } else {
                    plugin.staff().login(mod.getUniqueId());
                    plugin.messages().send(sender, "reportsystem-login");
                }
            }
            case "logout" -> {
                plugin.staff().logout(mod.getUniqueId());
                plugin.messages().send(sender, "reportsystem-logout");
            }
            case "list" -> plugin.reportGui().open(mod);
            default -> plugin.messages().send(sender, "unknown-action", "action", args[0]);
        }
        return true;
    }
}
