package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted player snapshot. {@code lastDisplayName} caches the most recent
 * formatted name as produced by CloudNet-Chat / nametag-plugins, so /lookup
 * can show a rank-coloured display even when the target is offline.
 * Empty string means "we never captured one" — UI falls back to the plain name.
 */
public record PlayerProfile(
        @NotNull UUID uuid,
        @NotNull String name,
        @NotNull Instant firstSeen,
        @NotNull Instant lastSeen,
        @NotNull String lastAddress,
        int lastTier,
        @NotNull String lastGroupName,
        @NotNull String lastDisplayName
) {
}
