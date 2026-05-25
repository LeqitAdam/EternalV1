package de.eternal.bungee;

import de.eternal.core.integration.CloudPermsAccess;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.OptionalInt;

/**
 * Bungee equivalent of the Spigot Tiers utility. Same semantics: the maximum
 * of the {@code eternal.tier.X} permission tier and the CloudNet
 * sortId/potency wins.
 */
public final class BungeeTiers {

    public static final int MAX_TIER = 100;

    private static CloudPermsAccess cloudPerms;

    private BungeeTiers() {
    }

    public static void init(@NotNull CloudPermsAccess access) {
        cloudPerms = access;
    }

    public static int of(@NotNull ProxiedPlayer p) {
        int permTier = 0;
        for (int i = MAX_TIER; i >= 0; i--) {
            if (p.hasPermission("eternal.tier." + i)) { permTier = i; break; }
        }
        int sortTier = 0;
        if (cloudPerms != null) {
            OptionalInt cn = cloudPerms.sortIdOf(p.getUniqueId());
            if (cn.isPresent()) sortTier = cn.getAsInt();
        }
        return Math.max(permTier, sortTier);
    }
}
