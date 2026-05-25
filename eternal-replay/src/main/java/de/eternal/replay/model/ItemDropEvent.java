package de.eternal.replay.model;

import org.jetbrains.annotations.NotNull;

/**
 * Ein Spieler hat einen ItemStack auf den Boden geworfen (Q-Taste) oder
 * ein Pickup wurde aufgehoben. {@code material} ist der Bukkit-Material-
 * Key, {@code amount} die Stackgröße. Position ist die Welt-Koordinate
 * wo der Drop landete bzw. wo er aufgehoben wurde.
 */
public record ItemDropEvent(
        int relativeMs,
        int playerIdx,
        boolean pickedUp,
        @NotNull String material,
        int amount,
        double x, double y, double z
) implements Recordable {
    @Override public @NotNull Type type() { return Type.ITEM_DROP; }
}
