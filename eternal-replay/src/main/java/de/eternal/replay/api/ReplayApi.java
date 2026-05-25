package de.eternal.replay.api;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public surface of the replay plugin. Other plugins use this to capture
 * snippets and trigger playback. Acquire via:
 * <pre>{@code
 * ReplayApi api = Bukkit.getServicesManager().load(ReplayApi.class);
 * }</pre>
 *
 * <p>The replay plugin registers itself in the Bukkit ServicesManager on
 * enable so consumers don't need a hard plugin dependency at compile time
 * — they can soft-depend on {@code EternalReplay} and check whether the
 * service is available at runtime.</p>
 *
 * <p>All methods are safe to call from the main server thread. Where
 * disk I/O is involved (persist, load), the implementation queues async
 * tasks internally and notifies via the returned id once persistence has
 * finished.</p>
 */
public interface ReplayApi {

    /**
     * Freeze the last N seconds of activity for {@code targetUuid} (plus
     * nearby players within sight) into a fresh recording, attach the
     * given source kind/id as searchable metadata, and KEEP recording
     * until {@link #endCapture(long)} is called or a configured maximum
     * follow-up duration elapses.
     *
     * @return the replay id, immediately usable for
     *         {@link #findReplay(long)} once {@link #endCapture(long)}
     *         has been called.
     */
    long captureWindow(@NotNull UUID targetUuid,
                       @NotNull ReplayKind kind,
                       @NotNull String sourceId,
                       @NotNull Map<String, Object> metadata);

    /**
     * Stop the trailing follow-up recording for {@code replayId} and
     * persist the buffer to disk asynchronously. After this call the
     * replay is read-only and discoverable via {@link #findBySource}
     * once the async write finishes.
     */
    void endCapture(long replayId);

    /**
     * Synchronous variant of {@link #endCapture(long)}. Blocks the
     * calling thread until the replay file has been written and indexed,
     * then returns the resolved handle. Returns empty when there was no
     * in-flight capture for this id.
     *
     * <p>Used by the report-system "Annehmen" flow so the mod can be
     * teleported into the freshly-frozen replay in a single round trip.
     * Slightly more expensive than the fire-and-forget variant — a small
     * replay persists in ~10-50ms.</p>
     */
    @NotNull Optional<ReplayHandle> endCaptureBlocking(long replayId);

    /** Looks up a stored replay by its id (post-{@link #endCapture}). */
    @NotNull Optional<ReplayHandle> findReplay(long replayId);

    /** Newest-first list of persisted replays, capped at {@code limit}.
     *  Powers /replay list and any future replay browser. */
    @NotNull java.util.List<ReplayHandle> listLatest(int limit);

    /** Latest replay matching a kind+sourceId pair, e.g. the replay
     *  attached to a specific report. */
    @NotNull Optional<ReplayHandle> findBySource(@NotNull ReplayKind kind, @NotNull String sourceId);

    /**
     * Begin playback for a viewer. The viewer's inventory + location are
     * snapshotted; the hotbar is replaced with the playback controls;
     * the viewer is teleported into the recorded scene. Ghost armor
     * stands are spawned for each tracked player and animate through the
     * recorded frames.
     *
     * <p>Stops automatically when the viewer disconnects or runs out of
     * frames; can be stopped early via {@link #stopPlayback(UUID)}.</p>
     */
    @NotNull PlaybackHandle play(@NotNull Player viewer, long replayId);

    /** Stop playback for a viewer and restore them to their pre-playback
     *  state (inventory + location). No-op if the viewer wasn't in
     *  playback. */
    void stopPlayback(@NotNull UUID viewerUuid);

    /** Reports whether a viewer is currently in a playback session. */
    boolean isViewing(@NotNull UUID viewerUuid);
}
