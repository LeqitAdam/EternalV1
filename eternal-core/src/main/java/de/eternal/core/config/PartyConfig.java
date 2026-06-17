package de.eternal.core.config;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Typed view of the {@code party:} section of config.yml. Shared by the Spigot
 * and Bungee party jars (both read an identical section, the same way
 * {@code cloudnet.groups} is mirrored on both sides). Parsed via
 * {@link Configs} so core stays free of Bukkit/Bungee YAML APIs.
 */
public record PartyConfig(
        boolean enabled,
        int headSlot,
        boolean giveHeadOnJoin,
        boolean lockHeadSlot,
        int maxMembers,
        int inviteExpirySeconds,
        boolean defaultAllowFriendRequests,
        boolean defaultAllowPartyInvites
) {
    public static @NotNull PartyConfig fromMap(@NotNull Map<String, Object> raw) {
        // slot is given 1-based in config ("slot 9" = the 9th hotbar slot) but
        // Bukkit inventories are 0-indexed; clamp + convert at read time so the
        // rest of the code can use it directly as an inventory index.
        int slot1Based = Configs.intOr(raw, "head-slot", 9);
        int headSlot = Math.max(0, Math.min(8, slot1Based - 1));
        return new PartyConfig(
                Configs.boolOr(raw, "enabled", true),
                headSlot,
                Configs.boolOr(raw, "give-head-on-join", true),
                Configs.boolOr(raw, "lock-head-slot", true),
                Math.max(2, Configs.intOr(raw, "max-members", 8)),
                Math.max(15, Configs.intOr(raw, "invite-expiry-seconds", 120)),
                Configs.boolOr(raw, "default-allow-friend-requests", true),
                Configs.boolOr(raw, "default-allow-party-invites", true)
        );
    }

    public static @NotNull PartyConfig defaults() {
        return fromMap(Map.of());
    }
}
