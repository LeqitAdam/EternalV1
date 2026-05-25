package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A pending action queued by the web dashboard for an online staff member —
 * e.g. "teleport Mod X to player Y". Spigot polls these and executes them
 * when the target staff is online.
 */
public record ActionEntry(
        long id,
        @NotNull String type,        // TELEPORT, ...
        @NotNull UUID targetStaffUuid,
        @NotNull String payload,     // JSON blob
        @NotNull Instant createdAt,
        @Nullable Instant consumedAt
) {
}
