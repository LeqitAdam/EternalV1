package de.eternal.autonicker.nick;

import de.eternal.core.config.AutonickerConfig;
import de.eternal.core.integration.CloudPermsAccess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * A rolled disguise: random name, a skin-owner to copy, and (CloudNet only) a
 * random NON-team rank to wear. {@code group} is null when no rank should be
 * applied (CloudNet absent, randomize disabled, or no eligible group).
 */
public record RandomIdentity(@NotNull String name, @NotNull String skinOwner, @Nullable String group) {

    public static @NotNull RandomIdentity pick(@NotNull AutonickerConfig config,
                                               @NotNull CloudPermsAccess cloudPerms,
                                               @NotNull Random random) {
        String name = rollName(config, random);
        String skin = config.skinPool().isEmpty() ? name
                : config.skinPool().get(random.nextInt(config.skinPool().size()));
        String group = rollGroup(config, cloudPerms, random);
        return new RandomIdentity(name, skin, group);
    }

    private static @NotNull String rollName(@NotNull AutonickerConfig config, @NotNull Random random) {
        List<String> pool = config.namePool();
        String base = pool.isEmpty() ? "Player" : pool.get(random.nextInt(pool.size()));
        if (config.appendRandomDigits()) {
            int digits = 1 + random.nextInt(3); // 1..3 digits
            StringBuilder sb = new StringBuilder(base);
            for (int i = 0; i < digits; i++) sb.append(random.nextInt(10));
            base = sb.toString();
        }
        // Minecraft names cap at 16 chars.
        return base.length() > 16 ? base.substring(0, 16) : base;
    }

    /** Chooses a CloudNet group to wear, honouring the nickable allowlist /
     *  protected denylist. Returns null when CloudNet is absent or nothing is
     *  eligible — so "always hide the youtube rank" is enforced by listing it
     *  under protected-groups. */
    private static @Nullable String rollGroup(@NotNull AutonickerConfig config,
                                              @NotNull CloudPermsAccess cloudPerms,
                                              @NotNull Random random) {
        if (!config.randomizeRank() || !cloudPerms.available()) return null;
        List<CloudPermsAccess.GroupInfo> all = cloudPerms.allGroups();
        if (all.isEmpty()) return null;
        List<String> nickable = config.nickableGroups();   // already lower-cased
        List<String> protectedGroups = config.protectedGroups(); // already lower-cased
        List<String> candidates = new ArrayList<>();
        for (CloudPermsAccess.GroupInfo g : all) {
            String lower = g.name().toLowerCase(Locale.ROOT);
            if (protectedGroups.contains(lower)) continue;
            if (!nickable.isEmpty() && !nickable.contains(lower)) continue;
            candidates.add(g.name()); // preserve CloudNet's exact case for setPrimaryGroup
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }
}
