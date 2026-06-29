package de.eternal.bungee.command;

import de.eternal.bungee.EternalBungee;
import de.eternal.core.model.PunishmentType;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.UUID;

public final class UnbanCommand extends Command implements net.md_5.bungee.api.plugin.TabExecutor {

    private final EternalBungee plugin;
    private final TargetResolver resolver;

    public UnbanCommand(@NotNull EternalBungee plugin) {
        super("unban", "eternal.unban");
        this.plugin = plugin;
        this.resolver = new TargetResolver(plugin.storage());
    }

    @Override
    public void execute(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 1) {
            sender.sendMessage(net.md_5.bungee.api.chat.TextComponent.fromLegacyText("/unban <Spieler> [Grund]"));
            return;
        }
        String name = args[0];
        String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : "Kein Grund angegeben";

        UUID issuerUuid = sender instanceof ProxiedPlayer p ? p.getUniqueId() : null;
        String issuerName = sender instanceof ProxiedPlayer p ? p.getName() : plugin.messages().get("console-name");

        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            var maybe = resolver.resolve(name);
            if (maybe.isEmpty()) {
                plugin.messages().send(sender, "unknown-player", "name", name);
                return;
            }
            var target = maybe.get();
            // Admin-only ban check: if the active ban was issued under an
            // admin reason, require eternal.unban.admin.
            var activeBan = plugin.punishments().activeBan(target.uuid());
            if (activeBan.isPresent()) {
                try {
                    int rid = Integer.parseInt(activeBan.get().reasonId());
                    var matchedReason = plugin.reasons().byId(rid);
                    if (matchedReason != null && matchedReason.adminOnly()
                            && !sender.hasPermission("eternal.unban.admin")) {
                        plugin.messages().send(sender, "unban-admin-only");
                        return;
                    }
                } catch (NumberFormatException ignored) { /* legacy non-numeric reason */ }
            }
            boolean ok = plugin.punishments().pardonActive(target.uuid(), PunishmentType.BAN,
                    issuerUuid, issuerName, reason);
            if (ok) plugin.messages().send(sender, "unban-success", "target", target.name());
            else plugin.messages().send(sender, "unban-not-banned", "target", target.name());
        });
    }
    @Override
    public Iterable<String> onTabComplete(net.md_5.bungee.api.CommandSender sender, String[] args) {
        return BungeeTab.players(plugin, args);
    }
}
