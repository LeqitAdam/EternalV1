package de.eternal.spigot.integration;

import de.eternal.replay.api.ReplayApi;
import de.eternal.replay.api.ReplayKind;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Thin façade around the optional {@link ReplayApi} service. EternalSpigot
 * doesn't have a hard dependency on the replay plugin — at runtime we
 * look up the service from Bukkit's ServicesManager. When the replay
 * plugin isn't installed, {@link #isAvailable()} returns false and every
 * other method becomes a no-op or returns empty.
 *
 * <p>That keeps Eternal usable on networks that don't want replay
 * recording (memory cost, etc.) while still being a single deploy
 * artefact when both are present.</p>
 */
public final class ReplayBridge {

    private final Logger log;
    private ReplayApi api;
    /** report-id → in-flight replay-id so endCaptureForReport can close the
     *  right one without callers having to remember it. Cleared once
     *  endCapture has been invoked. */
    private final java.util.Map<Long, Long> inFlight = new java.util.concurrent.ConcurrentHashMap<>();

    public ReplayBridge(@NotNull Logger log) {
        this.log = log;
        refresh();
    }

    /** Re-checks the ServicesManager. Useful after the replay plugin
     *  enables late (it's order-independent). */
    public void refresh() {
        RegisteredServiceProvider<ReplayApi> rsp = Bukkit.getServicesManager().getRegistration(ReplayApi.class);
        ReplayApi found = rsp == null ? null : rsp.getProvider();
        if (found != null && api == null) {
            log.info("ReplayApi service detected — reports will TP into replays.");
        }
        api = found;
    }

    public boolean isAvailable() {
        if (api == null) refresh();
        return api != null;
    }

    /** Triggered when a report is created — freezes the back-buffer for
     *  the reported player and starts the follow-up recording. Returns
     *  the replay id, or empty when replay is disabled. */
    public @NotNull Optional<Long> captureForReport(@NotNull UUID targetUuid, long reportId) {
        if (!isAvailable()) {
            log.warning("captureForReport(" + reportId + ") skipped — ReplayApi not registered");
            return Optional.empty();
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put("reportId", reportId);
        long replayId = api.captureWindow(targetUuid, ReplayKind.REPORT,
                String.valueOf(reportId), meta);
        inFlight.put(reportId, replayId);
        log.info("Replay capture started for report #" + reportId + " (target=" + targetUuid
                + ", replay-id=" + replayId + ")");
        return Optional.of(replayId);
    }

    /** End-capture by raw replay id (when the caller still holds it). */
    public void endCapture(@Nullable Long replayId) {
        if (!isAvailable() || replayId == null) return;
        api.endCapture(replayId);
    }

    /** Deletes EVERY replay attached to {@code reportId} on this server.
     *  Used by the close-without-ban + pardon-after-ban cleanup paths.
     *  Cross-server: each Spigot's ActionPoller tries to delete; only
     *  the one with the file actually succeeds, others no-op. */
    public void deleteReplayForReport(long reportId) {
        if (!isAvailable()) return;
        int removed = api.deleteReplaysBySource(ReplayKind.REPORT, String.valueOf(reportId));
        if (removed > 0) {
            log.info("Deleted " + removed + " replay(s) for report #" + reportId);
        }
    }

    /** End-capture by report id — looks up the in-flight replay we
     *  remembered in {@link #captureForReport} and persists it. */
    public void endCaptureForReport(long reportId) {
        Long replayId = inFlight.remove(reportId);
        if (replayId == null) {
            log.fine("endCaptureForReport(" + reportId + ") — no in-flight capture");
            return;
        }
        log.info("Ending replay capture for report #" + reportId + " (replay-id=" + replayId + ")");
        endCapture(replayId);
    }

    /** Stops every active playback session whose source is this report —
     *  used by the web-ban flow so the moderator who's currently inside
     *  the replay is pulled back out (otherwise they're stuck in
     *  spectator mode while the ban actually takes effect server-side). */
    public void stopAllPlaybackOfReport(long reportId) {
        if (!isAvailable()) return;
        int stopped = api.stopPlaybackBySource(ReplayKind.REPORT, String.valueOf(reportId));
        if (stopped > 0) {
            log.info("Stopped " + stopped + " in-progress replay playback(s) for report #" + reportId);
        }
    }

    /** Three-state outcome of {@link #tryPlayForReport}. The caller
     *  (typically {@code ActionPoller.teleport}) maps each value to a
     *  different user-visible behaviour. */
    public enum PlayAttempt {
        /** Replay opened — mod is now in spectator mode. Caller does
         *  nothing further. */
        PLAYING,
        /** No replay file exists and no in-flight capture either. The
         *  caller should fall back to a live teleport. */
        NO_REPLAY,
        /** A replay file does exist, but it contains no records for the
         *  target player — they were offline (or out of capture-range)
         *  for the entire recording window. Live-TP would fail too
         *  (player offline), so the caller should surface a friendly
         *  "subject was offline too long, no replay available" message
         *  instead of attempting either action. */
        EMPTY
    }

    /** Try to teleport the mod into the recorded replay for {@code reportId}.
     *  If the capture is still in-flight (mod accepted before the report
     *  was closed) we flush it synchronously so the replay becomes loadable
     *  in this same call. See {@link PlayAttempt} for the three possible
     *  outcomes. */
    public @NotNull PlayAttempt tryPlayForReport(@NotNull Player mod, long reportId, @NotNull java.util.UUID targetUuid) {
        if (!isAvailable()) {
            log.warning("tryPlayForReport(" + reportId + ") — ReplayApi not registered, falling back to live TP");
            return PlayAttempt.NO_REPLAY;
        }
        // Path 1 — in-flight: capture was opened at report-create and is
        // still running. Flush synchronously so the file exists right now.
        Long inFlightId = inFlight.remove(reportId);
        if (inFlightId != null) {
            log.info("tryPlayForReport(" + reportId + ") — flushing in-flight replay-id=" + inFlightId);
            api.endCaptureBlocking(inFlightId);
        }
        Optional<de.eternal.replay.api.ReplayHandle> maybe =
                api.findBySource(ReplayKind.REPORT, String.valueOf(reportId));

        // Path 2 — fallback: no in-flight capture (server restarted, race
        // condition, capture call dropped). Take whatever the recorder has
        // in its ring buffer NOW and persist that. Same end result: mod
        // gets dropped into a recording, just without the "follow-up after
        // report" segment.
        if (maybe.isEmpty()) {
            log.info("tryPlayForReport(" + reportId + ") — no in-flight, doing captureNow fallback");
            java.util.Map<String, Object> meta = new java.util.HashMap<>();
            meta.put("reportId", reportId);
            meta.put("fallback", true);
            maybe = api.captureNow(targetUuid, ReplayKind.REPORT, String.valueOf(reportId), meta);
        }

        if (maybe.isEmpty()) {
            log.warning("tryPlayForReport(" + reportId + ") — captureNow returned empty (recorder has no active buffers?)");
            return PlayAttempt.NO_REPLAY;
        }
        // Sanity check: does the persisted replay actually have ANY
        // records? The recorder only buffers online players, and if
        // everyone was offline when the snapshot ran, the file is just
        // a header with no content — playing it would dump the mod into
        // a frozen, featureless spectator session.
        //
        // ACHTUNG: we deliberately do NOT require records FOR the target
        // specifically. In a multi-server setup the captureNow fallback
        // runs on the MOD's spigot — if the target is online on a
        // different backend, their records won't be in this snapshot,
        // but other locally-online players might be. Showing those
        // ghosts (without the target) is still more useful than nothing,
        // and "no records at all" is the only state where blocking
        // playback is the right call.
        long replayId = maybe.get().id();
        if (!api.replayHasAnyRecords(replayId)) {
            log.warning("tryPlayForReport(" + reportId + ") — replay #" + replayId
                    + " has zero records (recorder was idle?), refusing to play");
            api.deleteReplay(replayId);
            return PlayAttempt.EMPTY;
        }
        log.info("tryPlayForReport(" + reportId + ") — playing replay #" + replayId
                + " (" + maybe.get().fileSizeBytes() + " bytes)");
        api.play(mod, replayId);
        return PlayAttempt.PLAYING;
    }
}
