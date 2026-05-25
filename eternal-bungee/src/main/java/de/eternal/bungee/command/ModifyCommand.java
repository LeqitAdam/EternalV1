package de.eternal.bungee.command;

import de.eternal.bungee.EternalBungee;
import de.eternal.core.model.PunishmentReason;
import de.eternal.core.time.DurationParser;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/** Same semantics as the Spigot ModifyCommand. */
public final class ModifyCommand extends Command {

    private final EternalBungee plugin;

    public ModifyCommand(@NotNull EternalBungee plugin) {
        super("modify");
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 3) {
            plugin.messages().send(sender, "usage-modify");
            return;
        }
        long banId;
        try { banId = Long.parseLong(args[0]); }
        catch (NumberFormatException ex) {
            plugin.messages().send(sender, "modify-invalid-id", "id", args[0]);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length));

        UUID modUuid = sender instanceof ProxiedPlayer p ? p.getUniqueId() : null;
        String modName = sender instanceof ProxiedPlayer p ? p.getName() : plugin.messages().get("console-name");

        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            var maybe = plugin.storage().findPunishmentById(banId);
            if (maybe.isEmpty()) {
                plugin.messages().send(sender, "modify-not-found", "id", banId);
                return;
            }
            switch (action) {
                case "setduration" -> applyDuration(sender, banId, value, modUuid, modName);
                case "setreason"   -> applyReason(sender, banId, value, modUuid, modName);
                default -> plugin.messages().send(sender, "modify-unknown-action", "action", action);
            }
        });
    }

    private void applyDuration(CommandSender sender, long banId, String raw, UUID modUuid, String modName) {
        if (!sender.hasPermission("eternal.modify.duration")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        long secs = DurationParser.parseToSeconds(raw);
        Instant newExpires = secs < 0 ? null : Instant.now().plusSeconds(secs);
        boolean ok = plugin.storage().modifyPunishmentDuration(banId, modUuid, modName, newExpires);
        if (!ok) plugin.messages().send(sender, "modify-not-found", "id", banId);
        else plugin.messages().send(sender, "modify-duration-success",
                "id", banId, "value", secs < 0 ? "permanent" : raw);
    }

    private void applyReason(CommandSender sender, long banId, String raw, UUID modUuid, String modName) {
        if (!sender.hasPermission("eternal.modify.reason")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        String newId, newLabel;
        try {
            int rid = Integer.parseInt(raw);
            PunishmentReason r = plugin.reasons().byId(rid);
            if (r == null) {
                plugin.messages().send(sender, "unknown-reason", "id", raw);
                return;
            }
            newId = String.valueOf(r.id());
            newLabel = r.label();
        } catch (NumberFormatException ignored) {
            newId = "custom";
            newLabel = raw;
        }
        boolean ok = plugin.storage().modifyPunishmentReason(banId, modUuid, modName, newId, newLabel);
        if (!ok) plugin.messages().send(sender, "modify-not-found", "id", banId);
        else plugin.messages().send(sender, "modify-reason-success", "id", banId, "value", newLabel);
    }
}
