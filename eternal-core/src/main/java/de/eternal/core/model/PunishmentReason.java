package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Template for a punishment reason loaded from config.
 *
 * <p>IDs are numeric (1..n), starting at 1, and may have gaps. The reason's
 * {@link #type()} decides whether picking it issues a ban or a mute, so
 * {@code /ban} and {@code /mute} share the same combined reason list.
 * {@code durationSeconds == -1} means permanent.</p>
 *
 * <p>Access control:</p>
 * <ul>
 *     <li>{@link #requiredPermission()} — null/empty means "default", i.e. requires
 *         {@code eternal.ban}. Otherwise the executor must hold exactly that node.</li>
 *     <li>{@link #requiredGroupId()} — optional CloudNet sort-id / potency.
 *         If &gt; 0, the executor's resolved tier must be at least this value.
 *         A held permission bypasses the group-id check.</li>
 *     <li>{@link #adminOnly()} — when true, only holders of
 *         {@code eternal.unban.admin} can pardon (and only holders of the reason's
 *         permission can apply it). The web UI treats those bans as read-only
 *         for non-admins.</li>
 * </ul>
 */
public record PunishmentReason(
        int id,
        @NotNull String label,
        @NotNull PunishmentType type,
        long durationSeconds,
        @Nullable String requiredPermission,
        int requiredGroupId,
        boolean adminOnly,
        @NotNull List<Long> escalationSeconds
) {

    public PunishmentReason {
        escalationSeconds = escalationSeconds == null ? List.of() : List.copyOf(escalationSeconds);
    }

    public boolean isPermanent() {
        return durationSeconds < 0;
    }

    public @Nullable Duration duration() {
        return isPermanent() ? null : Duration.ofSeconds(durationSeconds);
    }

    /** The effective node a normal executor must hold (defaults to eternal.ban). */
    public @NotNull String effectivePermission() {
        if (requiredPermission != null && !requiredPermission.isBlank()) return requiredPermission;
        return adminOnly ? "eternal.ban.admin" : "eternal.ban";
    }

    /**
     * Pick the duration that should apply on the {@code priorOffenseCount}-th
     * offense (0-based). When no escalation ladder is configured, the reason's
     * default duration applies for every offense.
     */
    public long resolveDurationFor(int priorOffenseCount) {
        if (escalationSeconds.isEmpty()) return durationSeconds;
        int idx = Math.min(priorOffenseCount, escalationSeconds.size() - 1);
        return escalationSeconds.get(idx);
    }

    public boolean hasEscalation() {
        return !escalationSeconds.isEmpty();
    }

    static @NotNull List<Long> emptyEscalation() {
        return Collections.emptyList();
    }
}
