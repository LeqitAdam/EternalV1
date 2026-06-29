package de.eternal.bungee.command;

import de.eternal.bungee.EternalBungee;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Command;
import org.jetbrains.annotations.NotNull;

/** Same semantics as the Spigot version. */
public final class ResetHistoryCommand extends Command implements net.md_5.bungee.api.plugin.TabExecutor {

    private final EternalBungee plugin;
    private final TargetResolver resolver;

    public ResetHistoryCommand(@NotNull EternalBungee plugin) {
        super("resethistory", "eternal.history.reset");
        this.plugin = plugin;
        this.resolver = new TargetResolver(plugin.storage());
    }

    @Override
    public void execute(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 1) {
            plugin.messages().send(sender, "usage-resethistory");
            return;
        }
        String name = args[0];
        boolean hard = plugin.coreConfig().history().hardReset();

        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            var maybe = resolver.resolve(name);
            if (maybe.isEmpty()) {
                plugin.messages().send(sender, "unknown-player", "name", name);
                return;
            }
            var target = maybe.get();
            int punishments = plugin.storage().resetPunishmentHistory(target.uuid(), hard);
            int reports = plugin.storage().resetReportHistory(target.uuid(), hard);
            int appeals = plugin.storage().resetAppealHistory(target.uuid());
            plugin.messages().send(sender, "resethistory-success",
                    "target", target.name(),
                    "count", punishments + reports + appeals,
                    "mode", hard ? "hard" : "soft");
        });
    }
    @Override
    public Iterable<String> onTabComplete(net.md_5.bungee.api.CommandSender sender, String[] args) {
        return BungeeTab.players(plugin, args);
    }
}
