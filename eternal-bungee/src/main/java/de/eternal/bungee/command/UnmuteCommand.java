package de.eternal.bungee.command;

import de.eternal.bungee.EternalBungee;
import de.eternal.core.model.PunishmentType;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.UUID;

public final class UnmuteCommand extends Command {

    private final EternalBungee plugin;
    private final TargetResolver resolver;

    public UnmuteCommand(@NotNull EternalBungee plugin) {
        super("unmute", "eternal.unmute");
        this.plugin = plugin;
        this.resolver = new TargetResolver(plugin.storage());
    }

    @Override
    public void execute(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 1) {
            sender.sendMessage(TextComponent.fromLegacyText("/unmute <Spieler> [Grund]"));
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
            boolean ok = plugin.punishments().pardonActive(target.uuid(), PunishmentType.MUTE,
                    issuerUuid, issuerName, reason);
            if (ok) plugin.messages().send(sender, "unmute-success", "target", target.name());
            else plugin.messages().send(sender, "unmute-not-muted", "target", target.name());
        });
    }
}
