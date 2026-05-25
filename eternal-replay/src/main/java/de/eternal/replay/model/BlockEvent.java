package de.eternal.replay.model;

import org.jetbrains.annotations.NotNull;

/**
 * Block place or break. {@code dataString} is the serialized BlockData
 * (e.g. {@code minecraft:stone}) — playback feeds it back through
 * {@code Bukkit.createBlockData} to re-render the change as a phantom
 * block for the viewer (without touching the actual world).
 */
public record BlockEvent(
        int relativeMs,
        int playerIdx,
        boolean placed,
        int blockX, int blockY, int blockZ,
        @NotNull String dataString
) implements Recordable {
    @Override public @NotNull Type type() {
        return placed ? Type.BLOCK_PLACE : Type.BLOCK_BREAK;
    }
}
