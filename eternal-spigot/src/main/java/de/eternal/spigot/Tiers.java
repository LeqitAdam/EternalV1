package de.eternal.spigot;

import de.eternal.core.integration.CloudPermsAccess;
import de.eternal.core.storage.EternalStorage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;
import org.jetbrains.annotations.NotNull;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * Maps a player to an integer "tier" — the higher the value, the higher the
 * rank.
 *
 * <p>Two sources contribute and the <em>maximum</em> of both wins, so neither
 * a forgotten permission node nor a missing CloudNet group can accidentally
 * down-grade a staff member:</p>
 * <ol>
 *     <li>Permissions of the form {@code eternal.tier.0..100} — highest one held.</li>
 *     <li>The user's highest CloudNet {@code sortId}/{@code potency} across all
 *         assigned permission groups. Resolved at call-time via
 *         {@link CloudPermsAccess#sortIdOf(UUID)}, or 0 when CloudNet isn't
 *         present.</li>
 * </ol>
 *
 * <p>Tiers gate only ban/unban actions — same rank cannot ban same rank, and
 * a higher rank is needed to override a lower-rank pardon. /lookup and
 * /history are <strong>not</strong> tier-gated.</p>
 */
public final class Tiers {

    public static final int MAX_TIER = 100;

    private static CloudPermsAccess cloudPerms;

    private Tiers() {
    }

    /** Wired once from EternalSpigot during boot. */
    public static void init(@NotNull CloudPermsAccess access) {
        cloudPerms = access;
    }

    public static int of(@NotNull Permissible p) {
        int permTier = 0;
        for (int i = MAX_TIER; i >= 0; i--) {
            if (p.hasPermission("eternal.tier." + i)) { permTier = i; break; }
        }
        int sortTier = 0;
        if (cloudPerms != null && p instanceof Player pl) {
            OptionalInt cn = cloudPerms.sortIdOf(pl.getUniqueId());
            if (cn.isPresent()) sortTier = cn.getAsInt();
        }
        return Math.max(permTier, sortTier);
    }

    /**
     * Tier of an offline player as snapshot from the database. Used by ban/unban
     * paths to decide whether the executor outranks a non-online target.
     */
    public static int ofOffline(@NotNull UUID uuid, @NotNull EternalStorage storage) {
        int sortTier = 0;
        if (cloudPerms != null) {
            OptionalInt cn = cloudPerms.sortIdOf(uuid);
            if (cn.isPresent()) sortTier = cn.getAsInt();
        }
        int stored = storage.findProfile(uuid).map(p -> p.lastTier()).orElse(0);
        return Math.max(sortTier, stored);
    }

    /**
     * @return true if {@code viewer} may issue or revoke a punishment against
     *         the target. Console always passes. {@code eternal.bypass}
     *         overrides too. Strictly-higher tier is required, so same-rank
     *         staff can't act on each other.
     */
    public static boolean canAct(@NotNull CommandSender viewer,
                                 @NotNull UUID targetUuid,
                                 @NotNull EternalStorage storage) {
        if (!(viewer instanceof Player vp)) return true;
        if (vp.hasPermission("eternal.bypass")) return true;
        int viewerTier = of(vp);
        Player onlineTarget = Bukkit.getPlayer(targetUuid);
        int targetTier = onlineTarget != null ? of(onlineTarget) : ofOffline(targetUuid, storage);
        return viewerTier > targetTier;
    }
}
