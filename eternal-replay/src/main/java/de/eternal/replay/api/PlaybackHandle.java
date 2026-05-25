package de.eternal.replay.api;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Handle to an active playback session. Survives as long as a viewer is
 * in replay mode. The plugin tracks one PlaybackHandle per viewer UUID
 * internally; this record lets callers query / control the session.
 */
public record PlaybackHandle(
        @NotNull UUID viewerUuid,
        long replayId,
        @NotNull ReplayHandle replay
) {
}
