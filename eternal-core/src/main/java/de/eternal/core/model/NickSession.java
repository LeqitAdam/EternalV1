package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted "this player is currently nicked" record. The disguise is purely
 * visual (a reflective {@code GameProfile} rewrite + a scoreboard team prefix);
 * it never changes the player's real CloudNet group, so a nicked player keeps
 * their own permissions. This row exists to restore the original name + skin on
 * unnick and to clear stale runtime state on startup. {@code skinValue}/
 * {@code skinSignature} are the raw Mojang texture property of the fake skin
 * (nullable when skin spoofing is unavailable). {@code originalGroup} and
 * {@code nickGroup} are diagnostics only (the real group is never mutated).
 */
public record NickSession(
        @NotNull UUID uuid,
        @NotNull String originalName,
        @NotNull String originalGroup,
        @NotNull String nickName,
        @NotNull String nickGroup,
        @Nullable String skinValue,
        @Nullable String skinSignature,
        @NotNull Instant startedAt
) {
}
