package de.eternal.replay.internal.playback;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Was ein Replay-Ghost können muss. Zwei Implementierungen:
 *
 * <ul>
 *     <li>{@link FakePlayer} — echte Spieler-NPC via ProtocolLib, mit
 *         Skin + Walking-Animation. Bevorzugt wenn ProtocolLib geladen
 *         ist.</li>
 *     <li>{@link GhostEntity} — ArmorStand mit Player-Head als Helm.
 *         Fallback ohne ProtocolLib.</li>
 * </ul>
 */
public interface GhostAvatar {

    /** One-shot warn flag so we don't spam logs every tick when FakePlayer
     *  reliably falls back to ArmorStand on every spawn. First failure
     *  emits a full stacktrace; subsequent ones stay silent. */
    AtomicBoolean WARNED = new AtomicBoolean(false);

    void teleport(@NotNull Location loc);

    void setMainHand(@Nullable ItemStack item);
    void setHelmet(@Nullable ItemStack item);
    void setChest(@Nullable ItemStack item);
    void setLeggings(@Nullable ItemStack item);
    void setBoots(@Nullable ItemStack item);

    void remove();

    /**
     * Probiert zuerst die FakePlayer-Implementation; fängt jeden
     * Throwable (ClassNotFoundException, NoClassDefFoundError,
     * ProtocolLib-Reflection-Fail) ab und fällt auf den ArmorStand-
     * Ghost zurück. Die erste Fehlermeldung pro Server-Lifetime wird
     * vollständig geloggt, damit man weiß warum gefallen wird.
     */
    static @NotNull GhostAvatar spawn(@NotNull Player viewer, @NotNull Location loc,
                                       @NotNull UUID uuid, @NotNull String name) {
        try {
            FakePlayer fp = new FakePlayer(viewer, uuid, name);
            fp.spawn(loc);
            return fp;
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                org.bukkit.Bukkit.getLogger().warning("[EternalReplay] FakePlayer spawn failed,"
                        + " falling back to ArmorStand for ghosts in THIS server-session."
                        + " Root cause: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                t.printStackTrace();
            }
            GhostEntity fallback = GhostEntity.spawn(loc, uuid, name);
            return new ArmorStandAvatar(fallback);
        }
    }
}
