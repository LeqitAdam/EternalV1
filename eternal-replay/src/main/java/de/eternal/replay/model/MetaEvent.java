package de.eternal.replay.model;

import org.jetbrains.annotations.NotNull;

/**
 * Sentinel event for mid-recording control changes — e.g. a new player
 * came into view, or a player logged out. Doesn't affect the visual
 * playback directly, but lets us add/remove ghost entities at the right
 * moment.
 */
public record MetaEvent(
        int relativeMs,
        int playerIdx,
        @NotNull Kind kind
) implements Recordable {
    public enum Kind { PLAYER_ADDED, PLAYER_REMOVED }

    @Override public @NotNull Type type() { return Type.META; }
}
