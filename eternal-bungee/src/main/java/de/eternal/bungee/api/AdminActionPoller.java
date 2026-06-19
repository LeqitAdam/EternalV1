package de.eternal.bungee.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.eternal.bungee.EternalBungee;
import de.eternal.core.model.ActionEntry;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Bungee-side poller for admin actions that are NOT bound to an online
 * player — currently just {@code CLOUDNET_GROUP} (change a player's
 * CloudNet rank). The Spigot {@code ActionPoller} only sees actions for
 * players online on its own server; this one runs on the proxy, which
 * is central and always present, and applies CloudNet mutations that
 * work for offline players too (the CloudNet permission store is
 * cluster-wide, not per-server).
 *
 * <p>Polls on a fixed interval on Bungee's async scheduler so the DB
 * round-trip doesn't touch the proxy main thread. Each handled action
 * is consumed immediately so a second proxy (HA setups) doesn't double-
 * apply it.</p>
 */
public final class AdminActionPoller {

    /** Action type for "set this player's CloudNet group". Must match
     *  the string the API queues in {@code Routes.changeUserGroup}. */
    public static final String TYPE_CLOUDNET_GROUP = "CLOUDNET_GROUP";
    /** Web-queued permission-refresh request. We fan it out to a
     *  per-online-player PERM_REFRESH the Spigot ActionPoller applies. */
    public static final String TYPE_PERM_REFRESH_REQUEST = "PERM_REFRESH_REQUEST";
    /** Web-queued grant to push into CloudPerms (group or user node). Only the
     *  proxy has the CloudNet driver, so the write happens here. */
    public static final String TYPE_CLOUDPERMS_WRITE = "CLOUDPERMS_WRITE";

    private final EternalBungee plugin;
    private ScheduledTask task;
    private ScheduledTask groupSyncTask;

    public AdminActionPoller(@NotNull EternalBungee plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // 2s interval is plenty — rank changes are rare and not latency-
        // sensitive. Async so the SQL query stays off the main thread.
        this.task = ProxyServer.getInstance().getScheduler().schedule(
                plugin, this::tick, 2L, 2L, TimeUnit.SECONDS);
        // Group-list sync: pushes the full CloudNet group catalogue into
        // the DB so the dashboard can show real ranks. 30s cadence — the
        // catalogue changes rarely; a DELETE+INSERT every 2s would be
        // wasteful. Runs once on start (after a 1s settle) then repeats.
        this.groupSyncTask = ProxyServer.getInstance().getScheduler().schedule(
                plugin, this::syncGroups, 1L, 30L, TimeUnit.SECONDS);
        plugin.getLogger().info("AdminActionPoller scheduled (CloudNet group changes + group-list sync).");
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        if (groupSyncTask != null) { groupSyncTask.cancel(); groupSyncTask = null; }
    }

    /**
     * Mirrors CloudNet's full group list into eternal_cloud_groups AND
     * auto-seeds a matching role per group, so "ranks" on the website are
     * exactly the CloudNet groups (the user's requirement: in-game ranks
     * and web groups must not differ). Role metadata (display name, sort
     * order, colour) is overwritten from CloudNet each cycle; the role's
     * permission grants are preserved (upsertRole only touches metadata).
     */
    private void syncGroups() {
        if (!plugin.cloudPerms().available()) return;
        try {
            var groups = plugin.cloudPerms().allGroups();
            if (groups.isEmpty()) return;
            plugin.storage().replaceCloudGroups(groups);

            // Mirror each group's OWN in-game permission nodes into the DB so the
            // standalone API (no CloudPerms) can resolve + prioritize them over
            // the web role config.
            java.util.Map<String, java.util.Map<String, Boolean>> groupPerms = new java.util.LinkedHashMap<>();
            for (var g : groups) {
                groupPerms.put(g.name(), plugin.cloudPerms().groupPermissions(g.name()));
            }
            plugin.storage().replaceCloudGroupPerms(groupPerms);

            if (plugin.storage() instanceof de.eternal.core.permission.PermissionStorage perms) {
                for (var g : groups) {
                    // Role name == group name so the binding is 1:1 and
                    // unambiguous. sortOrder mirrors CloudNet's sortId
                    // (lower = higher rank); colour carries the &-code.
                    var role = new de.eternal.core.model.Role(
                            g.name(), capitalize(g.name()), g.name(),
                            g.sortId(),
                            g.color().isEmpty() ? "&7" : g.color(),
                            java.time.Instant.now());
                    perms.upsertRole(role);
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("CloudNet group-list sync failed: " + ex.getMessage());
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void tick() {
        // Permission-refresh fanout works WITHOUT CloudNet (the Spigot
        // re-apply reads DB grants), so handle it before the CloudNet
        // availability gate.
        for (ActionEntry req : plugin.storage().pendingActionsByType(TYPE_PERM_REFRESH_REQUEST)) {
            try {
                handlePermRefreshRequest(req);
            } catch (Exception ex) {
                plugin.getLogger().warning("PERM_REFRESH_REQUEST #" + req.id() + " failed: " + ex.getMessage());
            } finally {
                plugin.storage().consumeAction(req.id());
            }
        }

        // CloudNet not present → nothing we can do with group actions.
        // Leave them queued; an operator can wire CloudNet later and
        // they'll apply on the next tick. (They never expire.)
        if (!plugin.cloudPerms().available()) return;

        for (ActionEntry action : plugin.storage().pendingActionsByType(TYPE_CLOUDNET_GROUP)) {
            try {
                handleGroupChange(action);
            } catch (Exception ex) {
                plugin.getLogger().warning("CLOUDNET_GROUP action #" + action.id()
                        + " failed: " + ex.getMessage());
            } finally {
                // Consume regardless of success — a permanently-failing
                // action (e.g. bad group name) shouldn't loop forever.
                // Failures are logged above for the operator.
                plugin.storage().consumeAction(action.id());
            }
        }

        // Web → CloudPerms: push dashboard grants into the CloudNet group/user
        // so they actually resolve in-game (CloudPerms owns the permissible).
        for (ActionEntry action : plugin.storage().pendingActionsByType(TYPE_CLOUDPERMS_WRITE)) {
            try {
                handleCloudPermsWrite(action);
            } catch (Exception ex) {
                plugin.getLogger().warning("CLOUDPERMS_WRITE action #" + action.id()
                        + " failed: " + ex.getMessage());
            } finally {
                plugin.storage().consumeAction(action.id());
            }
        }
    }

    /**
     * Payload: {@code {scope:"group|user", group?, uuid?, key, granted, clear}}.
     * Writes the node into CloudPerms — group grants affect every member, user
     * grants the one player. Both clear-able.
     */
    private void handleCloudPermsWrite(@NotNull ActionEntry action) {
        JsonObject body = JsonParser.parseString(action.payload()).getAsJsonObject();
        String scope = body.get("scope").getAsString();
        String key = body.get("key").getAsString();
        boolean clear = body.has("clear") && body.get("clear").getAsBoolean();
        boolean granted = body.has("granted") && body.get("granted").getAsBoolean();
        if ("group".equals(scope)) {
            String group = body.get("group").getAsString();
            boolean ok = clear ? plugin.cloudPerms().removeGroupPermission(group, key)
                               : plugin.cloudPerms().setGroupPermission(group, key, granted);
            if (!ok) plugin.getLogger().warning("CloudPerms group write failed: " + group + " / " + key);
        } else {
            UUID uuid = UUID.fromString(body.get("uuid").getAsString());
            boolean ok = clear ? plugin.cloudPerms().removeUserPermission(uuid, key)
                               : plugin.cloudPerms().setUserPermission(uuid, key, granted);
            if (!ok) plugin.getLogger().warning("CloudPerms user write failed: " + uuid + " / " + key);
        }
    }

    /**
     * Payload shape (JSON):
     * <pre>{ "uuid": "...", "group": "moderator", "op": "SET|ADD|REMOVE" }</pre>
     * SET replaces every group with {@code group}; ADD/REMOVE are
     * additive. After the CloudNet mutation we refresh the cached
     * group name on the player's profile so the dashboard's role
     * resolution + lookup stay in sync without waiting for a rejoin.
     */
    private void handleGroupChange(@NotNull ActionEntry action) {
        JsonObject body = JsonParser.parseString(action.payload()).getAsJsonObject();
        UUID uuid = UUID.fromString(body.get("uuid").getAsString());
        String group = body.get("group").getAsString();
        String op = body.has("op") ? body.get("op").getAsString().toUpperCase() : "SET";

        boolean ok = switch (op) {
            case "ADD" -> plugin.cloudPerms().addUserGroup(uuid, group);
            case "REMOVE" -> plugin.cloudPerms().removeUserGroup(uuid, group);
            default -> plugin.cloudPerms().setPrimaryGroup(uuid, group);
        };

        if (!ok) {
            plugin.getLogger().warning("CloudNet group " + op + " for " + uuid
                    + " → '" + group + "' returned false (group missing or user unknown?)");
            return;
        }
        plugin.getLogger().info("CloudNet group " + op + " applied: " + uuid + " → " + group);

        // Re-read the live group list so both the primary name and the
        // full set are accurate after the mutation. The full set powers
        // the dashboard's "already has / can add" view.
        java.util.List<String> liveGroups = plugin.cloudPerms().groupsOf(uuid);
        String newPrimary = op.equals("SET") ? group
                : liveGroups.stream().findFirst().orElse(group);
        plugin.storage().updateProfileGroup(uuid, newPrimary);
        plugin.storage().updateProfileGroups(uuid, liveGroups.isEmpty()
                ? java.util.List.of(newPrimary) : liveGroups);

        // Live in-game refresh: if the player is online anywhere on the
        // network, queue a PERM_REFRESH so their backend re-applies the
        // permission attachment with the new group + DB grants. Offline
        // players pick it up on next join automatically.
        if (ProxyServer.getInstance().getPlayer(uuid) != null) {
            plugin.storage().queueAction("PERM_REFRESH", uuid, "{}");
        }
    }

    /**
     * Turns a web-queued PERM_REFRESH_REQUEST into per-online-player
     * PERM_REFRESH actions the Spigot ActionPoller picks up. The proxy
     * is the only node that sees every online player network-wide, so
     * the fanout belongs here.
     *
     * Payload: {@code {scope:"user", uuid:"…"}} or
     *          {@code {scope:"role", role:"…"}}.
     */
    private void handlePermRefreshRequest(@NotNull ActionEntry req) {
        JsonObject body = JsonParser.parseString(req.payload()).getAsJsonObject();
        String scope = body.has("scope") ? body.get("scope").getAsString() : "user";

        if ("user".equals(scope)) {
            UUID uuid = UUID.fromString(body.get("uuid").getAsString());
            // Only queue when actually online — offline players get the
            // new perms on their next join, no action row to leak.
            if (ProxyServer.getInstance().getPlayer(uuid) != null) {
                plugin.storage().queueAction("PERM_REFRESH", uuid, "{}");
            }
            return;
        }

        if ("role".equals(scope)) {
            String roleName = body.get("role").getAsString();
            // Resolve the CloudNet group the role is bound to, then queue
            // a refresh for every online player in that group.
            String mcGroup = plugin.storage() instanceof de.eternal.core.permission.PermissionStorage perms
                    ? perms.findRole(roleName).map(de.eternal.core.model.Role::mcGroupName).orElse(null)
                    : null;
            if (mcGroup == null) return;
            for (var p : ProxyServer.getInstance().getPlayers()) {
                // groupsOf works for online players regardless of CloudNet
                // — if CN is absent it returns empty and we skip, which is
                // correct (no DB-driven perms without a group mapping).
                if (plugin.cloudPerms().groupsOf(p.getUniqueId()).stream()
                        .anyMatch(g -> g.equalsIgnoreCase(mcGroup))) {
                    plugin.storage().queueAction("PERM_REFRESH", p.getUniqueId(), "{}");
                }
            }
        }
    }
}
