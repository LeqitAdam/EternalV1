package de.eternal.spigot.command;

import de.eternal.core.model.PunishmentReason;
import de.eternal.core.time.DurationParser;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code /modify <banId> setduration <duration>} and
 * {@code /modify <banId> setreason <reasonId>}.
 *
 * <p>Permissions split per axis so trusted mods can adjust expiry without
 * also being able to rewrite the reason label, which is the more sensitive
 * change.</p>
 *
 * <p>Each modification stamps {@code modified_at} + {@code modified_by} on
 * the row so /history can show who tweaked the entry and when.</p>
 */
public final class ModifyCommand implements CommandExecutor {

    private final EternalSpigot plugin;

    public ModifyCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length < 3) {
            plugin.messages().send(sender, "usage-modify");
            return true;
        }
        long banId;
        try { banId = Long.parseLong(args[0]); }
        catch (NumberFormatException ex) {
            plugin.messages().send(sender, "modify-invalid-id", "id", args[0]);
            return true;
        }
        String action = args[1].toLowerCase(java.util.Locale.ROOT);
        String value = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));

        UUID modUuid = sender instanceof Player p ? p.getUniqueId() : null;
        String modName = sender instanceof Player p ? p.getName() : plugin.messages().get("console-name");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var maybe = plugin.storage().findPunishmentById(banId);
            if (maybe.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.messages().send(sender, "modify-not-found", "id", banId));
                return;
            }

            switch (action) {
                case "setduration" -> applyDuration(sender, banId, value, modUuid, modName);
                case "setreason"   -> applyReason(sender, banId, value, modUuid, modName);
                default -> Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.messages().send(sender, "modify-unknown-action", "action", action));
            }
        });
        return true;
    }

    private void applyDuration(@NotNull CommandSender sender, long banId, @NotNull String raw,
                               UUID modUuid, @NotNull String modName) {
        if (!sender.hasPermission("eternal.modify.duration")) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.messages().send(sender, "no-permission"));
            return;
        }
        long secs = DurationParser.parseToSeconds(raw);
        Instant newExpires = secs < 0 ? null : Instant.now().plusSeconds(secs);
        boolean ok = plugin.storage().modifyPunishmentDuration(banId, modUuid, modName, newExpires);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!ok) plugin.messages().send(sender, "modify-not-found", "id", banId);
            else plugin.messages().send(sender, "modify-duration-success",
                    "id", banId, "value", secs < 0 ? "permanent" : raw);
        });
    }

    private void applyReason(@NotNull CommandSender sender, long banId, @NotNull String raw,
                             UUID modUuid, @NotNull String modName) {
        if (!sender.hasPermission("eternal.modify.reason")) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.messages().send(sender, "no-permission"));
            return;
        }
        // Resolve: numeric → match by reason id; otherwise treat as raw label.
        String newId, newLabel;
        try {
            int rid = Integer.parseInt(raw);
            PunishmentReason r = plugin.reasons().byId(rid);
            if (r == null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.messages().send(sender, "unknown-reason", "id", raw));
                return;
            }
            newId = String.valueOf(r.id());
            newLabel = r.label();
        } catch (NumberFormatException ignored) {
            newId = "custom";
            newLabel = raw;
        }
        boolean ok = plugin.storage().modifyPunishmentReason(banId, modUuid, modName, newId, newLabel);
        String labelFinal = newLabel;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!ok) plugin.messages().send(sender, "modify-not-found", "id", banId);
            else plugin.messages().send(sender, "modify-reason-success",
                    "id", banId, "value", labelFinal);
        });
    }
}
