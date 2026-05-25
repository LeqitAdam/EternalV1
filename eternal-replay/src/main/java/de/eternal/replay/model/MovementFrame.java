package de.eternal.replay.model;

import org.jetbrains.annotations.NotNull;

/**
 * Per-tick (or every-N-ticks) snapshot of where a player is and what they
 * look like. The bulk of every replay file is movement frames — they're
 * the cheapest thing to capture and what playback needs most.
 *
 * <p>{@code mainHand} is a Bukkit material key (e.g. {@code minecraft:stone})
 * captured as a short string. We don't store the full ItemStack here —
 * that goes into {@link InventorySnapshot} once every few seconds.</p>
 */
public record MovementFrame(
        int relativeMs,
        int playerIdx,
        double x, double y, double z,
        float yaw, float pitch,
        boolean onGround,
        boolean sneaking,
        boolean sprinting,
        boolean flying,
        boolean swimming,
        @NotNull String mainHandMaterial
) implements Recordable {
    @Override public @NotNull Type type() { return Type.MOVEMENT; }
}
