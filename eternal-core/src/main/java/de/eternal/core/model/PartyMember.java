package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * One membership row tying a player to a {@link Party}. {@code role} is the
 * string {@code "LEADER"} or {@code "MEMBER"} — kept as text (not an enum
 * column) to match the existing VARCHAR-status convention and stay trivially
 * extensible (e.g. a future "MODERATOR" tier).
 */
public record PartyMember(
        long id,
        long partyId,
        @NotNull UUID uuid,
        @NotNull String name,
        @NotNull String role,
        @NotNull Instant joinedAt
) {
    public static final String ROLE_LEADER = "LEADER";
    public static final String ROLE_MEMBER = "MEMBER";

    public boolean isLeader() {
        return ROLE_LEADER.equalsIgnoreCase(role);
    }
}
