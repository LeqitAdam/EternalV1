package de.eternal.replay.model;

import org.jetbrains.annotations.NotNull;

/** Chat message captured for insult/spam reports. {@code message} is the
 *  raw, uncolored content the player sent. */
public record ChatEvent(
        int relativeMs,
        int playerIdx,
        @NotNull String message
) implements Recordable {
    @Override public @NotNull Type type() { return Type.CHAT; }
}
