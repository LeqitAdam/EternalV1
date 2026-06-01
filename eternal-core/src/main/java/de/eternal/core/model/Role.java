package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * Web-side role tied to a CloudNet group name. Players in the linked
 * CN group inherit this role's permission grants automatically; the
 * matching is by {@link #mcGroupName} (case-insensitive in lookups).
 *
 * <p>{@link #sortOrder} mirrors CloudNet's potency/sortId — used by
 * tier-protection logic the same way as the existing {@code lastTier}
 * value on profiles (max wins).</p>
 *
 * <p>{@link #color} is a Minecraft-style {@code &}-code shown in the
 * dashboard for the role badge — keeps the in-game and web look in
 * sync without a separate hex column.</p>
 */
public record Role(
        @NotNull String name,
        @NotNull String displayName,
        @NotNull String mcGroupName,
        int sortOrder,
        @NotNull String color,
        @NotNull Instant createdAt
) {
}
