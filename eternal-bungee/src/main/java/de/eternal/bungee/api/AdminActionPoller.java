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

    private final EternalBungee plugin;
    private ScheduledTask task;

    public AdminActionPoller(@NotNull EternalBungee plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // 2s interval is plenty — rank changes are rare and not latency-
        // sensitive. Async so the SQL query stays off the main thread.
        this.task = ProxyServer.getInstance().getScheduler().schedule(
                plugin, this::tick, 2L, 2L, TimeUnit.SECONDS);
        plugin.getLogger().info("AdminActionPoller scheduled (CloudNet group changes).");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        // CloudNet not present → nothing we can do with these actions.
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

        // Refresh the cached group name so the lookup / role mapping
        // reflects the change immediately. For SET we know the new
        // primary group; for ADD/REMOVE we re-read the live list and
        // take the first (highest-potency ordering isn't guaranteed
        // here, but the next join re-syncs precisely anyway).
        String newGroup = op.equals("SET") ? group
                : plugin.cloudPerms().groupsOf(uuid).stream().findFirst().orElse(group);
        plugin.storage().updateProfileGroup(uuid, newGroup);
    }
}
