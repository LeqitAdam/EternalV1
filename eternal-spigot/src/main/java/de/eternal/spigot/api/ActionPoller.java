package de.eternal.spigot.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Polls the REST API for actions queued by the web dashboard (e.g. teleport
 * requests) and executes them against the currently online staff members
 * registered in {@link de.eternal.core.staff.OnlineStaffRegistry}.
 *
 * Runs on Bukkit's async scheduler so the HTTP roundtrip doesn't block the
 * main thread; the actual teleport hops back onto the main thread.
 */
public final class ActionPoller {

    private final EternalSpigot plugin;
    private BukkitTask task;

    public ActionPoller(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // Wir schedulen immer — der tick() pruefen die Enabled-Flag jedes Mal,
        // damit /eternal reload spaeter den Bridge aktivieren kann ohne dass
        // der Server neugestartet werden muss.
        long periodTicks = Math.max(20L, plugin.apiBridge().config().pollIntervalSeconds() * 20L);
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, periodTicks, periodTicks);
        plugin.getLogger().info("ActionPoller scheduled (every "
                + plugin.apiBridge().config().pollIntervalSeconds() + "s) — tickt nur wenn api.enabled.");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (!plugin.apiBridge().enabled()) return;
        for (UUID uuid : plugin.staff().snapshot()) {
            for (ApiBridge.PendingAction action : plugin.apiBridge().pendingActions(uuid)) {
                handle(uuid, action);
                plugin.apiBridge().consumeAction(action.id());
            }
        }
    }

    private void handle(@NotNull UUID modUuid, @NotNull ApiBridge.PendingAction action) {
        switch (action.type()) {
            case "TELEPORT" -> teleport(modUuid, action.payload());
            default -> plugin.getLogger().warning("Unknown action type: " + action.type());
        }
    }

    private void teleport(@NotNull UUID modUuid, @NotNull String payload) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player mod = Bukkit.getPlayer(modUuid);
            if (mod == null) return;
            try {
                JsonObject body = JsonParser.parseString(payload).getAsJsonObject();
                UUID targetUuid = UUID.fromString(body.get("targetUuid").getAsString());
                Player target = Bukkit.getPlayer(targetUuid);
                if (target == null) {
                    plugin.messages().send(mod, "reportsystem-tp-offline");
                    return;
                }
                mod.teleport(target.getLocation());
                plugin.messages().send(mod, "reportsystem-tp-success", "target", target.getName());
            } catch (Exception ex) {
                plugin.getLogger().warning("Teleport action payload invalid: " + ex.getMessage());
            }
        });
    }
}
