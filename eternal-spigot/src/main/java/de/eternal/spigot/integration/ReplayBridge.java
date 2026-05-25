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

    /** Try to teleport the mod into the recorded replay for {@code reportId}.
     *  If the capture is still in-flight (mod accepted before the report
     *  was closed) we flush it synchronously so the replay becomes loadable
     *  in this same call. Returns false when no recording exists at all —
     *  caller should fall back to a live teleport. */
    public boolean tryPlayForReport(@NotNull Player mod, long reportId) {
        if (!isAvailable()) {
            log.warning("tryPlayForReport(" + reportId + ") — ReplayApi not registered, falling back to live TP");
            return false;
        }
        // If we're still recording, finish on the spot so play() has a
        // file to read. This is the common path: mod accepts a fresh
        // report → capture has been running ~30s → flush → play.
        Long inFlightId = inFlight.remove(reportId);
        if (inFlightId != null) {
            log.info("tryPlayForReport(" + reportId + ") — flushing in-flight replay-id=" + inFlightId);
            api.endCaptureBlocking(inFlightId);
        }
        Optional<de.eternal.replay.api.ReplayHandle> maybe =
                api.findBySource(ReplayKind.REPORT, String.valueOf(reportId));
        if (maybe.isEmpty()) {
            log.warning("tryPlayForReport(" + reportId + ") — no persisted replay found "
                    + "(was capture started? was the server restarted between report+accept?)");
            return false;
        }
        log.info("tryPlayForReport(" + reportId + ") — playing replay #" + maybe.get().id()
                + " (" + maybe.get().fileSizeBytes() + " bytes)");
        api.play(mod, maybe.get().id());
        return true;
    }
}
