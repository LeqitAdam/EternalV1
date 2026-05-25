package de.eternal.replay.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Metadata for a persisted replay. The actual frames live in a separate
 * binary file at {@link #filePath()} — call
 * {@link ReplayApi#play(org.bukkit.entity.Player, long)} to start
 * playback, which reads the file lazily.
 */
public record ReplayHandle(
        long id,
        @NotNull ReplayKind kind,
        /** Free-form source id — e.g. the Eternal report id (as decimal string),
         *  or the BedWars match id. {@code null} for MANUAL captures. */
        @Nullable String sourceId,
        @NotNull String serverName,
        @NotNull Instant startedAt,
        @NotNull Instant endedAt,
        @NotNull Path filePath,
        long fileSizeBytes,
        @Nullable UUID primaryPlayerUuid,
        @NotNull String primaryPlayerName,
        @NotNull List<UUID> otherPlayerUuids
) {
    public long durationSeconds() {
        return Math.max(0, (endedAt.toEpochMilli() - startedAt.toEpochMilli()) / 1000);
    }
}
