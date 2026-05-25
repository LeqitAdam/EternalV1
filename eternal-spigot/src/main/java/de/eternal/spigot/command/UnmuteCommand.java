package de.eternal.spigot.command;

import de.eternal.core.model.PunishmentType;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class UnmuteCommand implements CommandExecutor {

    private final EternalSpigot plugin;
    private final TargetResolver resolver;

    public UnmuteCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
        this.resolver = new TargetResolver(plugin.storage());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("eternal.unmute")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length < 1) {
            plugin.messages().send(sender, "usage-unmute");
            return true;
        }
        String name = args[0];
        String reason = args.length > 1
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))
                : "Kein Grund angegeben";

        UUID issuerUuid = sender instanceof Player p ? p.getUniqueId() : null;
        String issuerName = sender instanceof Player p ? p.getName() : plugin.messages().get("console-name");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var maybe = resolver.resolve(name);
            if (maybe.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.messages().send(sender, "unknown-player", "name", name));
                return;
            }
            var target = maybe.get();
            boolean ok = plugin.punishments().pardonActive(target.uuid(), PunishmentType.MUTE,
                    issuerUuid, issuerName, reason);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ok) plugin.messages().send(sender, "unmute-success", "target", target.name());
                else plugin.messages().send(sender, "unmute-not-muted", "target", target.name());
            });
        });
        return true;
    }
}
