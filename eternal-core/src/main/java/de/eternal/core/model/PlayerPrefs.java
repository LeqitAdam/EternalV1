package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Per-player social preferences shared by the party and autonicker features.
 * A missing row reads as {@link #defaults(UUID)} so the storage never returns
 * null. {@code autonickDefaultDisplay} overrides the config-wide default
 * display format when set (null = use config default).
 */
public record PlayerPrefs(
        @NotNull UUID uuid,
        boolean allowFriendRequests,
        boolean allowPartyInvites,
        @Nullable String autonickDefaultDisplay
) {
    public static @NotNull PlayerPrefs defaults(@NotNull UUID uuid) {
        return new PlayerPrefs(uuid, true, true, null);
    }

    public @NotNull PlayerPrefs withAllowFriendRequests(boolean v) {
        return new PlayerPrefs(uuid, v, allowPartyInvites, autonickDefaultDisplay);
    }

    public @NotNull PlayerPrefs withAllowPartyInvites(boolean v) {
        return new PlayerPrefs(uuid, allowFriendRequests, v, autonickDefaultDisplay);
    }

    public @NotNull PlayerPrefs withAutonickDefaultDisplay(@Nullable String v) {
        return new PlayerPrefs(uuid, allowFriendRequests, allowPartyInvites, v);
    }
}
