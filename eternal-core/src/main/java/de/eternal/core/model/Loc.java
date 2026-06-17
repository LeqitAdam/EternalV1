package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;

/**
 * A serialisable world location (world name + coords + orientation). Platform
 * code converts to/from Bukkit {@code Location}. Stored by the base-system
 * tables (homes, warps, spawn); kept platform-agnostic so it lives in core.
 */
public record Loc(
        @NotNull String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
}
