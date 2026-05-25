package de.eternal.replay.model;

import org.jetbrains.annotations.NotNull;

/**
 * One entity hit another. {@code damagerIdx} is -1 when the damager isn't
 * a tracked player (mob/environmental). Used to reconstruct fights and
 * spot KillAura / Reach during playback.
 */
public record HitEvent(
        int relativeMs,
        int damagerIdx,
        int victimIdx,
        double damage,
        @NotNull String weaponMaterial,
        double reachDistance
) implements Recordable {
    @Override public @NotNull Type type() { return Type.HIT; }
}
