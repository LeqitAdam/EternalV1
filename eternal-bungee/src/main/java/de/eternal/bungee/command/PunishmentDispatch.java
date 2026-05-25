package de.eternal.bungee.command;

import de.eternal.bungee.BungeeTiers;
import de.eternal.bungee.EternalBungee;
import de.eternal.core.model.PunishmentReason;
import de.eternal.core.model.PunishmentType;
import de.eternal.core.time.DurationParser;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Bungee mirror of the Spigot dispatch — same numeric-id flow, same combined
 * reason list, same tier check.
 */
public final class PunishmentDispatch {

    private final EternalBungee plugin;
    private final TargetResolver resolver;
    private final PunishmentActions actions;

    public PunishmentDispatch(@NotNull EternalBungee plugin) {
        this.plugin = plugin;
        this.resolver = new TargetResolver(plugin.storage());
        this.actions = new PunishmentActions(plugin);
    }

    public void handle(@NotNull CommandSender sender, @NotNull String usageKey, @NotNull String[] args) {
        if (args.length < 1) {
            plugin.messages().send(sender, usageKey);
            return;
        }
        String targetName = args[0];

        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            var maybe = resolver.resolve(targetName);
            if (maybe.isEmpty()) {
                plugin.messages().send(sender, "unknown-player", "name", targetName);
                return;
            }
            var target = maybe.get();

            if (args.length < 2) {
                showReasons(sender, target.name());
                return;
            }

            int id;
            try { id = Integer.parseInt(args[1]); }
            catch (NumberFormatException ex) {
                plugin.messages().send(sender, "unknown-reason", "id", args[1]);
                return;
            }

            PunishmentReason reason = plugin.reasons().byId(id);
            if (reason == null) {
                plugin.messages().send(sender, "unknown-reason", "id", id);
                return;
            }
            String effPerm = reason.effectivePermission();
            boolean hasPerm = sender.hasPermission(effPerm);
            if (!hasPerm) {
                boolean groupOk = reason.requiredGroupId() > 0
                        && sender instanceof net.md_5.bungee.api.connection.ProxiedPlayer pp
                        && de.eternal.bungee.BungeeTiers.of(pp) >= reason.requiredGroupId();
                if (!groupOk) {
                    plugin.messages().send(sender, "ban-perm-denied");
                    return;
                }
            }
            if (!canAct(sender, target.uuid())) {
                plugin.messages().send(sender, "tier-blocked-action", "target", target.name());
                return;
            }

            boolean alreadyActive = reason.type() == PunishmentType.BAN
                    ? plugin.punishments().activeBan(target.uuid()).isPresent()
                    : plugin.punishments().activeMute(target.uuid()).isPresent();
            if (alreadyActive) {
                String key = reason.type() == PunishmentType.BAN ? "ban-already-banned" : "mute-already-muted";
                plugin.messages().send(sender, key, "target", target.name());
                return;
            }
            actions.apply(sender, target.uuid(), target.name(), reason);
        });
    }

    private boolean canAct(@NotNull CommandSender sender, @NotNull UUID targetUuid) {
        if (!(sender instanceof ProxiedPlayer vp)) return true;
        if (vp.hasPermission("eternal.bypass")) return true;
        int viewerTier = BungeeTiers.of(vp);
        ProxiedPlayer online = ProxyServer.getInstance().getPlayer(targetUuid);
        int targetTier = online != null ? BungeeTiers.of(online)
                : plugin.storage().findProfile(targetUuid).map(p -> p.lastTier()).orElse(0);
        return viewerTier > targetTier;
    }

    private void showReasons(@NotNull CommandSender to, @NotNull String targetName) {
        plugin.messages().send(to, "punish-list-header", "target", targetName);
        for (PunishmentReason r : plugin.reasons().all()) {
            boolean hasPerm = to.hasPermission(r.effectivePermission());
            boolean groupOk = r.requiredGroupId() > 0
                    && to instanceof net.md_5.bungee.api.connection.ProxiedPlayer pp
                    && de.eternal.bungee.BungeeTiers.of(pp) >= r.requiredGroupId();
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
