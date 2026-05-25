package de.eternal.replay.internal;

import de.eternal.replay.api.PlaybackHandle;
import de.eternal.replay.api.ReplayApi;
import de.eternal.replay.api.ReplayHandle;
import de.eternal.replay.api.ReplayKind;
import de.eternal.replay.internal.playback.PlaybackSession;
import de.eternal.replay.internal.recorder.ContinuousRecorder;
import de.eternal.replay.internal.recorder.PlayerBuffer;
import de.eternal.replay.internal.storage.ReplayStore;
import de.eternal.replay.model.Recordable;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Singleton implementation of {@link ReplayApi}. Wires together the
 * {@link ContinuousRecorder} (always-on buffer) with the {@link ReplayStore}
 * (persistence) and the playback engine. Registered in the Bukkit
 * ServicesManager on plugin enable.
 */
public final class ReplayApiImpl implements ReplayApi {

    private final JavaPlugin plugin;
    private final Logger log;
    private final ContinuousRecorder recorder;
    private final ReplayStore store;
    private final String serverName;
    private final long maxFollowupMs;

    /** Captures-in-flight: replay-id-to-be → metadata so we know what to
     *  persist when {@link #endCapture(long)} is called. */
    private final Map<Long, PendingCapture> pending = new ConcurrentHashMap<>();
    /** Auto-incrementing id reserved before persistence (so {@link #captureWindow}
     *  can return one without blocking). */
    private final java.util.concurrent.atomic.AtomicLong nextReplayId =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    private final ConcurrentHashMap<UUID, PlaybackSession> playbacks = new ConcurrentHashMap<>();

    public ReplayApiImpl(@NotNull JavaPlugin plugin,
                         @NotNull ContinuousRecorder recorder,
                         @NotNull ReplayStore store,
                         @NotNull String serverName,
                         long maxFollowupMs) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.recorder = recorder;
        this.store = store;
        this.serverName = serverName;
        this.maxFollowupMs = maxFollowupMs;
    }

    @Override
    public long captureWindow(@NotNull UUID targetUuid, @NotNull ReplayKind kind,
                              @NotNull String sourceId, @NotNull Map<String, Object> metadata) {
        long id = nextReplayId.incrementAndGet();
        // Snapshot the back-buffer NOW so even if endCapture takes a while
        // we don't lose the pre-report context to retention trimming.
        Map<UUID, List<Recordable>> initial = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        for (PlayerBuffer buf : recorder.allBuffers()) {
            initial.put(buf.uuid(), buf.snapshot());
            names.put(buf.uuid(), buf.name());
        }
        long startedAt = System.currentTimeMillis() - recorder.retentionMs();
        pending.put(id, new PendingCapture(id, kind, sourceId, targetUuid, names, initial, startedAt));

        // Hard timeout: if endCapture is never called we still close the
        // file after maxFollowupMs so disk/RAM doesn't grow forever.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (pending.containsKey(id)) endCapture(id);
        }, Math.max(1, maxFollowupMs / 50));
        return id;
    }

    @Override
    public void endCapture(long replayId) {
        PendingCapture pc = pending.remove(replayId);
        if (pc == null) return;
        // Persist async — recorders shouldn't be blocked by disk I/O.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> persistNow(replayId, pc));
    }

    @Override
    public @NotNull Optional<de.eternal.replay.api.ReplayHandle> endCaptureBlocking(long replayId) {
        PendingCapture pc = pending.remove(replayId);
        if (pc == null) return Optional.empty();
        return Optional.ofNullable(persistNow(replayId, pc));
    }

    /** Single source-of-truth for the persist step — used both async
     *  (fire-and-forget) and sync (block until on disk + indexed). */
    private @org.jetbrains.annotations.Nullable de.eternal.replay.api.ReplayHandle persistNow(long replayId, @NotNull PendingCapture pc) {
        // Merge the initial snapshot with everything that landed in the
        // buffer SINCE captureWindow — that's the "follow-up" segment.
        Map<UUID, List<Recordable>> finalRecords = new HashMap<>(pc.initialBuffers);
        for (PlayerBuffer buf : recorder.allBuffers()) {
            List<Recordable> latest = buf.snapshot();
            List<Recordable> initial = pc.initialBuffers.get(buf.uuid());
            if (initial == null) {
                finalRecords.put(buf.uuid(), latest);
                pc.playerNames.put(buf.uuid(), buf.name());
                continue;
            }
            int initialSize = initial.size();
            int lastRelMs = initialSize > 0 ? initial.get(initialSize - 1).relativeMs() : -1;
            for (Recordable r : latest) {
                if (r.relativeMs() > lastRelMs) initial.add(r);
            }
            finalRecords.put(buf.uuid(), initial);
        }
        String primaryName = pc.playerNames.getOrDefault(pc.primaryUuid, pc.primaryUuid.toString());
        try {
            var handle = store.persist(pc.kind, pc.sourceId, serverName,
                    pc.primaryUuid, primaryName, pc.playerNames, finalRecords,
                    Instant.ofEpochMilli(pc.startedAt), Instant.now());
            log.info("Replay " + handle.id() + " persisted (" + pc.kind + ", source=" + pc.sourceId
                    + ", " + handle.fileSizeBytes() + " bytes)");
            return handle;
        } catch (IOException ex) {
            log.log(Level.WARNING, "Failed to persist replay (pending id " + replayId + ")", ex);
            return null;
        }
    }

    @Override
    public @NotNull Optional<ReplayHandle> findReplay(long replayId) {
        return store.find(replayId);
    }

    @Override
    public @NotNull java.util.List<ReplayHandle> listLatest(int limit) {
        return store.listLatest(limit);
    }

    @Override
    public @NotNull Optional<ReplayHandle> findBySource(@NotNull ReplayKind kind, @NotNull String sourceId) {
        return store.findBySource(kind, sourceId);
    }

    @Override
    public @NotNull PlaybackHandle play(@NotNull Player viewer, long replayId) {
        Optional<ReplayHandle> maybe = findReplay(replayId);
        if (maybe.isEmpty()) throw new IllegalArgumentException("replay not found: " + replayId);
        PlaybackSession existing = playbacks.remove(viewer.getUniqueId());
        if (existing != null) existing.stop();
        PlaybackSession s = new PlaybackSession(viewer, maybe.get(), store, log,
                () -> playbacks.remove(viewer.getUniqueId()));
        playbacks.put(viewer.getUniqueId(), s);
        s.start(plugin);
        return s.apiHandle();
    }

    @Override
    public void stopPlayback(@NotNull UUID viewerUuid) {
        PlaybackSession s = playbacks.remove(viewerUuid);
        if (s != null) s.stop();
    }

    @Override
    public boolean isViewing(@NotNull UUID viewerUuid) {
        return playbacks.containsKey(viewerUuid);
    }

    public PlaybackSession sessionOf(@NotNull Player viewer) {
        return playbacks.get(viewer.getUniqueId());
    }

    /** Flushes every in-flight capture synchronously. Called from
     *  EternalReplay.onDisable so a server restart doesn't lose in-progress
     *  recordings (the reports they belong to live on in the DB). */
    public void flushAllPending() {
        if (pending.isEmpty()) return;
        log.info("Flushing " + pending.size() + " in-flight replay capture(s) on disable...");
        for (long id : new java.util.ArrayList<>(pending.keySet())) {
            endCaptureBlocking(id);
        }
    }

    /** State carried for a capture between {@link #captureWindow} and
     *  {@link #endCapture}. */
    private record PendingCapture(
            long id,
            @NotNull ReplayKind kind,
            @NotNull String sourceId,
            @NotNull UUID primaryUuid,
            @NotNull Map<UUID, String> playerNames,
            @NotNull Map<UUID, List<Recordable>> initialBuffers,
            long startedAt
    ) {}
}
