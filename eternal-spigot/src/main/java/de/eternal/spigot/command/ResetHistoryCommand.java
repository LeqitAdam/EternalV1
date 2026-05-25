package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * /resethistory &lt;player&gt; — wipes a target player's recorded punishments
 * and reports. The {@code history.reset-mode} config toggle decides whether
 * rows are physically dropped (hard, default) or simply hidden (soft).
 *
 * <p>This is intentionally not undoable from in-game; admins who picked soft
 * mode can recover via direct SQL by clearing {@code hidden=1}.</p>
 */
public final class ResetHistoryCommand implements CommandExecutor {

    private final EternalSpigot plugin;
    private final TargetResolver resolver;

    public ResetHistoryCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
        this.resolver = new TargetResolver(plugin.storage());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("eternal.history.reset")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length < 1) {
            plugin.messages().send(sender, "usage-resethistory");
            return true;
        }
        String name = args[0];
        boolean hard = plugin.coreConfig().history().hardReset();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var maybe = resolver.resolve(name);
            if (maybe.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.messages().send(sender, "unknown-player", "name", name));
                return;
            }
            var target = maybe.get();
            int punishments = plugin.storage().resetPunishmentHistory(target.uuid(), hard);
            int reports = plugin.storage().resetReportHistory(target.uuid(), hard);
            Bukkit.getScheduler().runTask(plugin, () ->
                    plugin.messages().send(sender, "resethistory-success",
                            "target", target.name(),
                            "count", punishments + reports,
                            "mode", hard ? "hard" : "soft"));
        });
        return true;
    }
}
