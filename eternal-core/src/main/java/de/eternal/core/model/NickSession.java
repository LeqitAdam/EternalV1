package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted "this player is currently nicked" record. Written BEFORE the
 * destructive CloudNet {@code setPrimaryGroup} call so a crash mid-nick is
 * recoverable: on startup the autonicker restores every active session's
 * original group/name. {@code skinValue}/{@code skinSignature} are the raw
 * Mojang texture property of the fake skin (nullable when skin spoofing is
 * disabled or unavailable). {@code originalGroup} is empty when CloudNet was
 * absent at nick time (nothing to restore).
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
