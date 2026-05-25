package de.eternal.spigot.command;

import de.eternal.core.model.PunishmentReason;
import de.eternal.core.model.PunishmentType;
import de.eternal.core.time.DurationParser;
import de.eternal.spigot.EternalSpigot;
import de.eternal.spigot.Tiers;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * Shared logic behind {@code /ban} and {@code /mute}. Both commands accept
 * the same combined reason list — the {@link PunishmentReason#type()} stored
 * with the picked reason decides whether the result is a ban or a mute.
 */
public final class PunishmentDispatch {

    private final EternalSpigot plugin;
    private final TargetResolver resolver;
    private final PunishmentActions actions;

    public PunishmentDispatch(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
        this.resolver = new TargetResolver(plugin.storage());
        this.actions = new PunishmentActions(plugin);
    }

    public void handle(@NotNull CommandSender sender, @NotNull String usageKey,
                       @NotNull String[] args) {
        if (args.length < 1) {
            plugin.messages().send(sender, usageKey);
            return;
        }
        String targetName = args[0];

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var maybe = resolver.resolve(targetName);
            if (maybe.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.messages().send(sender, "unknown-player", "name", targetName));
                return;
            }
            var target = maybe.get();

            // No reason id given -> print the combined list and exit.
            if (args.length < 2) {
                Bukkit.getScheduler().runTask(plugin, () -> showReasons(sender, target.name()));
                return;
            }

            int id;
            try { id = Integer.parseInt(args[1]); }
            catch (NumberFormatException ex) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.messages().send(sender, "unknown-reason", "id", args[1]));
                return;
            }

            PunishmentReason reason = plugin.reasons().byId(id);
            if (reason == null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.messages().send(sender, "unknown-reason", "id", id));
                return;
            }

            // Reason-specific gating:
            //  - explicit permission node (defaults to eternal.ban, or eternal.ban.admin
            //    when the reason is marked admin-only)
            //  - optional CloudNet sort-id / potency floor (groupid in reasons.yml)
            //  Holding the permission is enough on its own; the groupid check is
            //  only consulted when no specific permission was configured.
            String effPerm = reason.effectivePermission();
            boolean hasPerm = sender.hasPermission(effPerm);
            if (!hasPerm) {
                if (reason.requiredGroupId() > 0
                        && sender instanceof org.bukkit.entity.Player pp
                        && de.eternal.spigot.Tiers.of(pp) >= reason.requiredGroupId()) {
                    // groupId floor satisfied — proceed.
                } else {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            plugin.messages().send(sender, "ban-perm-denied"));
                    return;
                }
            }

            // Tier check — runs synchronously on the main thread because it
            // touches Bukkit.getPlayer().
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!Tiers.canAct(sender, target.uuid(), plugin.storage())) {
                    plugin.messages().send(sender, "tier-blocked-action", "target", target.name());
                    return;
                }
                // Same-type already active?
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean alreadyActive = reason.type() == PunishmentType.BAN
                            ? plugin.punishments().activeBan(target.uuid()).isPresent()
                            : plugin.punishments().activeMute(target.uuid()).isPresent();
                    if (alreadyActive) {
                        String key = reason.type() == PunishmentType.BAN ? "ban-already-banned" : "mute-already-muted";
                        Bukkit.getScheduler().runTask(plugin, () ->
                                plugin.messages().send(sender, key, "target", target.name()));
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () ->
                            actions.apply(sender, target.uuid(), target.name(), reason));
                });
            });
        });
    }

    private void showReasons(@NotNull CommandSender to, @NotNull String targetName) {
        plugin.messages().send(to, "punish-list-header", "target", targetName);
        for (PunishmentReason r : plugin.reasons().all()) {
            String perm = r.effectivePermission();
            boolean hasPerm = to.hasPermission(perm);
            boolean groupOk = r.requiredGroupId() > 0
                    && to instanceof org.bukkit.entity.Player pp
                    && Tiers.of(pp) >= r.requiredGroupId();
            if (!hasPerm && !groupOk) continue;
            String key = r.type() == PunishmentType.BAN ? "punish-list-line-ban" : "punish-list-line-mute";
            String duration = r.isPermanent() ? "permanent" : DurationParser.formatRemaining(r.durationSeconds());
            plugin.messages().send(to, key,
                    "id", r.id(),
                    "label", r.label(),
                    "duration", duration);
        }
        plugin.messages().send(to, "punish-list-footer", "target", targetName);
    }
}
