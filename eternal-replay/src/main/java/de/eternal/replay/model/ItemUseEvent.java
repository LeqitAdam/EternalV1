package de.eternal.replay.model;

import org.jetbrains.annotations.NotNull;

/**
 * Right-click / use action. {@code action} is the Bukkit Action enum name
 * (RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK, LEFT_CLICK_AIR, LEFT_CLICK_BLOCK).
 * Useful for spotting AutoSoup / AutoClicker patterns in replays.
 */
public record ItemUseEvent(
        int relativeMs,
        int playerIdx,
        @NotNull String action,
        @NotNull String material
) implements Recordable {
    @Override public @NotNull Type type() { return Type.ITEM_USE; }
}
