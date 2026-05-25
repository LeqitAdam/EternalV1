package de.eternal.replay.model;

import org.jetbrains.annotations.NotNull;

/**
 * Periodic snapshot (default every 5s) of a player's entire inventory.
 * Captured as base64-encoded Bukkit-serialized ItemStacks so the playback
 * can render real items in the ghost player's hands / equipment slots.
 *
 * <p>Format: a single base64 string carrying the whole 41-slot array
 * (36 main + 4 armour + 1 offhand) Bukkit-serialized. Decoder lives in
 * {@code InventoryCodec}.</p>
 */
public record InventorySnapshot(
        int relativeMs,
        int playerIdx,
        @NotNull String base64Data
) implements Recordable {
    @Override public @NotNull Type type() { return Type.INVENTORY; }
}
