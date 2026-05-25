package de.eternal.replay.internal.storage;

import de.eternal.replay.api.ReplayHandle;
import de.eternal.replay.api.ReplayKind;
import de.eternal.replay.model.Recordable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Disk-backed storage for replay files + an in-memory metadata index.
 *
 * <p>Replay files live at {@code <dataFolder>/replays/<id>.dat.gz}; metadata
 * is kept as a single JSON-ish text file ({@code index.txt}) appended-to
 * on persist and reloaded on plugin start. Keeping the index out of the
 * main Eternal SQL database means the replay plugin can run completely
 * standalone — no DB-dep, no schema migrations.</p>
 *
 * <p>For very large servers we could swap this for an SQLite metadata
 * file later; for typical CloudNet setups the flat text index is plenty
 * (replays are a small handful per day).</p>
 */
public final class ReplayStore {

    private final Path baseDir;
    private final Path indexFile;
    private final Map<Long, ReplayHandle> byId = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    public ReplayStore(@NotNull Path baseDir) throws IOException {
        this.baseDir = baseDir;
        Files.createDirectories(baseDir);
        this.indexFile = baseDir.resolve("index.txt");
        if (Files.exists(indexFile)) loadIndex();
    }

    public @NotNull Optional<ReplayHandle> find(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    public @NotNull Optional<ReplayHandle> findBySource(@NotNull ReplayKind kind, @NotNull String sourceId) {
        return byId.values().stream()
                .filter(h -> h.kind() == kind && sourceId.equals(h.sourceId()))
                .reduce((a, b) -> a.id() > b.id() ? a : b); // latest by id
    }

    /**
     * Persist a fresh replay. Records are sorted by relativeMs across all
     * source buffers; each buffer becomes one player index in the file
     * header. The viewer's primary focus is the buffer matching
     * {@code primaryUuid}.
     */
    public @NotNull ReplayHandle persist(
            @NotNull ReplayKind kind,
            @Nullable String sourceId,
            @NotNull String serverName,
            @NotNull UUID primaryUuid,
            @NotNull String primaryName,
            @NotNull Map<UUID, String> playerNamesByUuid,
            @NotNull Map<UUID, List<Recordable>> recordsByUuid,
            @NotNull Instant startedAt,
            @NotNull Instant endedAt) throws IOException {

        long id = nextId.getAndIncrement();
        Path file = baseDir.resolve(id + ".dat.gz");

        // Assemble the player header in a stable order: primary first,
        // then others in name-sorted order so playback diffs are
        // reproducible across runs.
        List<UUID> orderedUuids = new ArrayList<>();
        orderedUuids.add(primaryUuid);
        playerNamesByUuid.keySet().stream()
                .filter(u -> !u.equals(primaryUuid))
                .sorted((a, b) -> playerNamesByUuid.getOrDefault(a, "").compareTo(playerNamesByUuid.getOrDefault(b, "")))
                .forEach(orderedUuids::add);

        Map<UUID, Integer> idxByUuid = new HashMap<>();
        List<ReplayCodec.PlayerRef> refs = new ArrayList<>();
        for (int i = 0; i < orderedUuids.size(); i++) {
            UUID u = orderedUuids.get(i);
            String n = u.equals(primaryUuid) ? primaryName
                    : playerNamesByUuid.getOrDefault(u, u.toString());
            idxByUuid.put(u, i);
            refs.add(new ReplayCodec.PlayerRef(u, n));
        }

        // Merge all records, sorted by relativeMs.
        record Pair(int idx, Recordable rec) {}
        List<Pair> merged = new ArrayList<>();
        for (var entry : recordsByUuid.entrySet()) {
            int idx = idxByUuid.getOrDefault(entry.getKey(), -1);
            if (idx < 0) continue;
            for (Recordable r : entry.getValue()) merged.add(new Pair(idx, r));
        }
        merged.sort((a, b) -> Integer.compare(a.rec.relativeMs(), b.rec.relativeMs()));

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file))))) {
            ReplayCodec.writeHeader(out, startedAt.toEpochMilli(), refs);
            for (Pair p : merged) ReplayCodec.writeRecord(out, p.idx, p.rec);
        }

        long size = Files.size(file);
        List<UUID> others = orderedUuids.stream().filter(u -> !u.equals(primaryUuid)).toList();
        ReplayHandle handle = new ReplayHandle(
                id, kind, sourceId, serverName, startedAt, endedAt, file, size,
                primaryUuid, primaryName, others);
        byId.put(id, handle);
        appendIndex(handle);
        return handle;
    }

    /* -------------------- read frames during playback -------------------- */

    /** Opens the replay file for streaming. The caller is responsible for
     *  closing the returned stream. */
    public @NotNull DataInputStream openForRead(@NotNull ReplayHandle handle) throws IOException {
        return new DataInputStream(new java.io.BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(handle.filePath()))));
    }

    /* ---------------------------- index file ---------------------------- */

    /** Format: {@code id|kind|sourceId|server|started|ended|file|size|primary|name|others}
     *  with {@code |} as separator. {@code others} is comma-separated UUIDs. */
    private void appendIndex(@NotNull ReplayHandle h) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(h.id()).append('|')
          .append(h.kind().name()).append('|')
          .append(h.sourceId() == null ? "" : h.sourceId()).append('|')
          .append(h.serverName()).append('|')
          .append(h.startedAt().toEpochMilli()).append('|')
          .append(h.endedAt().toEpochMilli()).append('|')
          .append(baseDir.relativize(h.filePath())).append('|')
          .append(h.fileSizeBytes()).append('|')
          .append(h.primaryPlayerUuid() == null ? "" : h.primaryPlayerUuid()).append('|')
          .append(h.primaryPlayerName()).append('|');
        for (int i = 0; i < h.otherPlayerUuids().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(h.otherPlayerUuids().get(i));
        }
        sb.append('\n');
        Files.writeString(indexFile, sb.toString(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private void loadIndex() throws IOException {
        long maxId = 0;
        for (String line : Files.readAllLines(indexFile)) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|", -1);
            if (parts.length < 11) continue;
            try {
                long id = Long.parseLong(parts[0]);
                ReplayKind kind = ReplayKind.valueOf(parts[1]);
                String sourceId = parts[2].isEmpty() ? null : parts[2];
                String server = parts[3];
                Instant started = Instant.ofEpochMilli(Long.parseLong(parts[4]));
                Instant ended = Instant.ofEpochMilli(Long.parseLong(parts[5]));
                Path file = baseDir.resolve(parts[6]);
                long size = Long.parseLong(parts[7]);
                UUID primaryUuid = parts[8].isEmpty() ? null : UUID.fromString(parts[8]);
                String primaryName = parts[9];
                List<UUID> others = new ArrayList<>();
                if (!parts[10].isEmpty()) {
                    for (String s : parts[10].split(",")) others.add(UUID.fromString(s));
                }
                byId.put(id, new ReplayHandle(id, kind, sourceId, server, started, ended,
                        file, size, primaryUuid, primaryName, others));
                if (id >= maxId) maxId = id;
            } catch (Exception ignored) { /* skip malformed line */ }
        }
        nextId.set(maxId + 1);
    }
}
