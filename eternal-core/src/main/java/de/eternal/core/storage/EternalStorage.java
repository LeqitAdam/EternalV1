package de.eternal.core.storage;

import de.eternal.core.model.ActionEntry;
import de.eternal.core.model.LinkCode;
import de.eternal.core.model.PlayerProfile;
import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.model.PunishmentType;
import de.eternal.core.model.ReportEntry;
import de.eternal.core.model.Session;
import de.eternal.core.model.StaffStat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EternalStorage extends AutoCloseable {

    void init();

    @Override
    void close();

    /**
     * Upsert player snapshot. {@code displayName} may be empty when the call
     * site has no chat-plugin-formatted name handy — in that case the
     * previously stored value is preserved.
     */
    void recordProfile(@NotNull UUID uuid, @NotNull String name, @NotNull String address,
                       int lastTier, @NotNull String lastGroupName, @NotNull String displayName);

    /** Legacy overload kept so older callers still compile. */
    default void recordProfile(@NotNull UUID uuid, @NotNull String name, @NotNull String address,
                                int lastTier, @NotNull String lastGroupName) {
        recordProfile(uuid, name, address, lastTier, lastGroupName, "");
    }

    /** Updates ONLY the cached group name on an existing profile row,
     *  without touching name/address/tier/displayName. Used after a
     *  web-driven CloudNet group change so the dashboard's role
     *  resolution + lookup reflect the new rank before the player
     *  rejoins. No-op when the profile doesn't exist yet. */
    void updateProfileGroup(@NotNull UUID uuid, @NotNull String groupName);

    @NotNull Optional<PlayerProfile> findProfile(@NotNull UUID uuid);

    @NotNull Optional<PlayerProfile> findProfileByName(@NotNull String name);

    long insertPunishment(@NotNull PunishmentEntry entry);

    @NotNull Optional<PunishmentEntry> findActivePunishment(@NotNull UUID target, @NotNull PunishmentType type);

    @NotNull List<PunishmentEntry> findPunishmentHistory(@NotNull UUID target, @Nullable PunishmentType type);

    /**
     * Counts past, non-active punishments of the given target with the given
     * reason id. Used by the escalation ladder to decide how harsh the next
     * sanction should be — active punishments are ignored so a player who is
     * currently serving a sentence doesn't double-count themselves.
     */
    int countPriorOffenses(@NotNull UUID target, @NotNull String reasonId, @NotNull PunishmentType type);

    /** Rewrites a punishment's expiry. {@code newExpires == null} means permanent. */
    boolean modifyPunishmentDuration(long id, @Nullable UUID modifierUuid, @NotNull String modifierName,
                                      @org.jetbrains.annotations.Nullable java.time.Instant newExpires);

    /** Rewrites a punishment's reason id+label. */
    boolean modifyPunishmentReason(long id, @Nullable UUID modifierUuid, @NotNull String modifierName,
                                    @NotNull String newReasonId, @NotNull String newReasonLabel);

    /**
     * Wipes a player's recorded history. Behaviour controlled by {@code hard}:
     * <ul>
     *     <li>{@code true} (default) — DELETE FROM, rows are gone for good.</li>
     *     <li>{@code false} — sets {@code hidden=1}, history queries skip them.</li>
     * </ul>
     * Active punishments are pardoned first so the player isn't accidentally
     * left in a banned state with an empty history.
     */
    int resetPunishmentHistory(@NotNull UUID target, boolean hard);

    /** Sibling to {@link #resetPunishmentHistory} that wipes reports too. */
    int resetReportHistory(@NotNull UUID target, boolean hard);

    @NotNull List<PunishmentEntry> findAllActive(@NotNull PunishmentType type);

    @NotNull Optional<PunishmentEntry> findPunishmentById(long id);

    boolean pardonPunishment(long id, @Nullable UUID issuerUuid, @NotNull String issuerName, @NotNull String reason);

    long insertReport(@NotNull ReportEntry entry);

    @NotNull List<ReportEntry> findOpenReports();

    @NotNull List<ReportEntry> findReportsByTarget(@NotNull UUID target);

    /**
     * Paginated report fetch. {@code statuses} may be empty to fetch all
     * statuses. Limit is capped to a sensible upper bound internally.
     */
    @NotNull List<ReportEntry> findReports(@NotNull java.util.Collection<de.eternal.core.model.ReportStatus> statuses,
                                            int limit, int offset);

    long countReports(@NotNull java.util.Collection<de.eternal.core.model.ReportStatus> statuses);

    @NotNull Optional<ReportEntry> findReport(long id);

    boolean claimReport(long id, @NotNull UUID handlerUuid, @NotNull String handlerName);

    /**
     * Overrides whichever handler currently owns the report (also works when
     * the report is already CLAIMED by someone else — used by higher-tier
     * staff to take over from lower-tier handlers).
     */
    boolean takeOverReport(long id, @NotNull UUID handlerUuid, @NotNull String handlerName);

    boolean closeReport(long id, @NotNull String resolution);

    @NotNull List<StaffStat> staffStats();

    /* --- Login-Sessions ------------------------------------------------- */

    long startSession(@NotNull UUID uuid, @NotNull String name, @NotNull String ip);

    void endSession(long sessionId);

    @NotNull List<de.eternal.core.model.LoginSession> recentSessions(@NotNull UUID uuid, int limit);

    /* --- Report -> Ban Verlinkung -------------------------------------- */

    /** Verbindet einen Report mit dem Bann, der daraus resultierte. */
    boolean linkReportToBan(long reportId, long banId);

    /** Bann-ID die zu einem Report fuehrte (oder leer). */
    @NotNull java.util.Optional<Long> findBanForReport(long reportId);

    /** Inverse: alle Reports die zu diesem Bann verlinkt wurden. Wird vom
     *  Unban-Flow genutzt um zugehoerige Replays zu loeschen. */
    @NotNull List<ReportEntry> findReportsByBanId(long banId);

    /* --- Account-Link & Web-Sessions ------------------------------------ */

    void createLinkCode(@NotNull LinkCode code);

    @NotNull Optional<LinkCode> findLinkByCode(@NotNull String code);

    @NotNull Optional<LinkCode> findLinkByToken(@NotNull String linkToken);

    boolean confirmLinkCode(@NotNull String code, @NotNull UUID uuid, @NotNull String name);

    boolean markLinkConsumed(@NotNull String linkToken);

    void createSession(@NotNull Session session);

    @NotNull Optional<Session> findSession(@NotNull String token);

    boolean deleteSession(@NotNull String token);

    /**
     * All not-yet-expired web sessions. Used by the admin dashboard's
     * "active users" overview — distinguishes staff from regular players
     * via the {@link Session#role()} field.
     */
    @NotNull List<Session> listActiveSessions();

    /* --- Unban-Antraege ------------------------------------------------- */

    long createAppeal(@NotNull de.eternal.core.model.UnbanAppeal appeal);

    @NotNull Optional<de.eternal.core.model.UnbanAppeal> findAppeal(long id);

    @NotNull List<de.eternal.core.model.UnbanAppeal> findAppealsByApplicant(@NotNull UUID applicant);

    @NotNull List<de.eternal.core.model.UnbanAppeal> findAppealsByStatus(@NotNull de.eternal.core.model.UnbanAppeal.Status status);

    boolean decideAppeal(long id, @NotNull UUID reviewerUuid, @NotNull String reviewerName,
                          @NotNull de.eternal.core.model.UnbanAppeal.Status decision,
                          @NotNull String decisionReason);

    /* --- Web -> Ingame Action-Queue ------------------------------------- */

    long queueAction(@NotNull String type, @NotNull UUID targetStaff, @NotNull String payload);

    @NotNull List<ActionEntry> pendingActionsFor(@NotNull UUID targetStaff);

    /** All un-consumed actions of one {@code type}, regardless of which
     *  target UUID they carry. Used by the Bungee-side admin poller for
     *  actions that aren't bound to an online player — e.g. changing an
     *  OFFLINE player's CloudNet group, which any node with driver access
     *  can apply centrally. */
    @NotNull List<ActionEntry> pendingActionsByType(@NotNull String type);

    boolean consumeAction(long id);

    /* --- GDPR consent --------------------------------------------------- */

    /** True when this UUID has explicitly accepted the privacy policy.
     *  False both for "never asked" and "declined" (we purge declined
     *  rows so they read identical here). */
    boolean hasConsent(@NotNull UUID uuid);

    /** Records the explicit acceptance + the IP we're allowed to log. */
    void recordConsent(@NotNull UUID uuid, @NotNull String name, @NotNull String ip);

    /** Deletes all personal-data rows for {@code uuid} on the eternal
     *  side (profiles, sessions, login logs, link codes, user
     *  permissions, the consent row itself). Returns total deleted.
     *  Moderation history (punishments, reports, appeals, replays)
     *  is kept on the legitimate-interest legal basis. */
    int purgePersonalData(@NotNull UUID uuid);
}
