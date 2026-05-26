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
        // Iterate every online player — actions queued by the website
        // target a specific UUID, but the mod doesn't need to be in
        // /reportsystem login state for us to dispatch them. The
        // teleport handler will auto-log them in if needed.
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            for (ApiBridge.PendingAction action : plugin.apiBridge().pendingActions(uuid)) {
                handle(uuid, action);
                plugin.apiBridge().consumeAction(action.id());
            }
        }
    }

    private void handle(@NotNull UUID modUuid, @NotNull ApiBridge.PendingAction action) {
        switch (action.type()) {
            case "TELEPORT" -> teleport(modUuid, action.payload());
            case "KICK" -> kick(modUuid, action.payload());
            case "DELETE_REPLAY" -> deleteReplay(action.payload());
            case "END_CAPTURE" -> endCapture(action.payload());
            case "BROADCAST" -> broadcast(modUuid, action.payload());
            default -> plugin.getLogger().warning("Unknown action type: " + action.type());
        }
    }

    /** Web-ban/-mute fanout: payload carries a fully formatted broadcast
     *  line (with &amp;-codes resolved by the API). We hand it to Bungee
     *  via plugin-message on the {@code eternal:staff-broadcast} channel,
     *  which then iterates every proxied player with the notify perm —
     *  same code path as the in-game /ban broadcast. Falls back to a
     *  local-only broadcast when the player has no backend connection
     *  (vanishingly rare, but cheap to guard against). */
    private void broadcast(@NotNull UUID carrierUuid, @NotNull String payload) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player carrier = Bukkit.getPlayer(carrierUuid);
            if (carrier == null) return;
            String message;
            try {
                JsonObject body = JsonParser.parseString(payload).getAsJsonObject();
                message = body.has("message") ? body.get("message").getAsString() : "";
            } catch (Exception ex) {
                plugin.getLogger().warning("BROADCAST payload invalid: " + ex.getMessage());
                return;
            }
            if (message.isEmpty()) return;

            // Forward to Bungee for cross-server fanout via plugin-message.
            // The channel must be pre-registered for sendPluginMessage to
            // succeed; lazy-register on first use. Bungee's
            // StaffBroadcastListener handles the actual filtering+sending
            // to every proxied player with the eternal.notify perm — that
            // also includes the staff on THIS spigot, so we deliberately
            // don't do a second local broadcast (would duplicate the line
            // in chat).
            String channel = "eternal:staff-broadcast";
            if (!plugin.getServer().getMessenger().isOutgoingChannelRegistered(plugin, channel)) {
                plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, channel);
            }
            com.google.common.io.ByteArrayDataOutput out = com.google.common.io.ByteStreams.newDataOutput();
            out.writeUTF("staff-notify");
            out.writeUTF(message);
            carrier.sendPluginMessage(plugin, channel, out.toByteArray());
        });
    }

    /** Force-kicks the player targeted by {@code modUuid} (this action is
     *  queued AGAINST the banned player, not the mod). Used by the web
     *  ban-from-report flow so a banned online player is removed
     *  immediately instead of next-rejoin. */
    private void kick(@NotNull UUID targetUuid, @NotNull String payload) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player target = Bukkit.getPlayer(targetUuid);
            if (target == null) return;
            String kickReason;
            try {
                JsonObject body = JsonParser.parseString(payload).getAsJsonObject();
                kickReason = body.has("reason") ? body.get("reason").getAsString()
                        : "Du wurdest gebannt.";
                // Wenn die Action auch einen formatierten Kick-Screen mitliefert,
                // den nehmen (er sieht hübscher aus mit Bann-ID + Dauer).
                if (body.has("screen")) kickReason = body.get("screen").getAsString();
            } catch (Exception ex) { kickReason = "Du wurdest gebannt."; }
            target.kickPlayer(org.bukkit.ChatColor.translateAlternateColorCodes('&', kickReason));
        });
    }

    /** Triggered by the web close-without-ban flow. Tries to delete the
     *  replay file on THIS server; no-op when the file lives elsewhere
     *  (other servers in the cluster will also pick this action up via
     *  their own polling). Also stops any active playback of this replay
     *  so a mod still watching it doesn't see ghosts continue once the
     *  underlying file is gone. */
    private void deleteReplay(@NotNull String payload) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                JsonObject body = JsonParser.parseString(payload).getAsJsonObject();
                long reportId = body.get("reportId").getAsLong();
                plugin.replayBridge().stopAllPlaybackOfReport(reportId);
                plugin.replayBridge().deleteReplayForReport(reportId);
            } catch (Exception ex) {
                plugin.getLogger().warning("DELETE_REPLAY payload invalid: " + ex.getMessage());
            }
        });
    }

    /** Spigot-side end-capture for a report — usually fired by the API on
     *  report-close or web-ban to flush the in-flight buffer to disk.
     *  Also pulls any moderator who is currently inside the replay back
     *  to their pre-playback state, so they don't stay in spectator mode
     *  while the ban kicks in or the report is resolved. */
    private void endCapture(@NotNull String payload) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                JsonObject body = JsonParser.parseString(payload).getAsJsonObject();
                long reportId = body.get("reportId").getAsLong();
                plugin.replayBridge().endCaptureForReport(reportId);
                plugin.replayBridge().stopAllPlaybackOfReport(reportId);
            } catch (Exception ex) {
                plugin.getLogger().warning("END_CAPTURE payload invalid: " + ex.getMessage());
            }
        });
    }

    private void teleport(@NotNull UUID modUuid, @NotNull String payload) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player mod = Bukkit.getPlayer(modUuid);
            if (mod == null) return;
            try {
                JsonObject body = JsonParser.parseString(payload).getAsJsonObject();
                UUID targetUuid = UUID.fromString(body.get("targetUuid").getAsString());
                long reportId = body.has("reportId") ? body.get("reportId").getAsLong() : -1L;

                // Auto-login the mod into /reportsystem if they aren't yet
                // — clicking "Annehmen" in the web UI implies on-duty.
                if (plugin.staff().login(mod.getUniqueId())) {
                    plugin.messages().send(mod, "reportsystem-login");
                }

                // Replay-first: if a replay exists (or can be captured on
                // the fly), drop the mod into that instead of live-TP.
                // Same code path as the in-game accept flow. Three-state
                // outcome: PLAYING (we're done), NO_REPLAY (fall back to
                // live-TP below), EMPTY (no records for the target —
                // friendly error, no live-TP attempt either since the
                // player is obviously not around).
                if (reportId > 0) {
                    var attempt = plugin.replayBridge().tryPlayForReport(mod, reportId, targetUuid);
                    switch (attempt) {
                        case PLAYING -> {
                            plugin.messages().send(mod, "report-replay-started", "id", reportId);
                            return;
                        }
                        case EMPTY -> {
                            plugin.messages().send(mod, "report-replay-empty");
                            return;
                        }
                        case NO_REPLAY -> { /* fall through to live-TP path */ }
                    }
                }

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
