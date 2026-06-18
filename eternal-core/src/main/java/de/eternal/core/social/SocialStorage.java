package de.eternal.core.social;

import de.eternal.core.model.Friend;
import de.eternal.core.model.FriendRequest;
import de.eternal.core.model.NickSession;
import de.eternal.core.model.Party;
import de.eternal.core.model.PartyInvite;
import de.eternal.core.model.PartyMember;
import de.eternal.core.model.PlayerPrefs;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read/write surface for the social feature tables (friends, parties, invites,
 * per-player preferences and nick sessions). Implemented directly by
 * {@link de.eternal.core.storage.sql.SqlStorage}, mirroring the
 * {@link de.eternal.core.permission.PermissionStorage} split so the central
 * {@link de.eternal.core.storage.EternalStorage} contract stays focused on
 * moderation.
 *
 * <p>All methods are safe to call from any thread — the Hikari pool handles
 * concurrency. Reads never return {@code null} (empty list / {@code Optional}
 * / {@link PlayerPrefs#defaults(UUID)} instead).</p>
 */
public interface SocialStorage {

    /* --- friendships ---------------------------------------------------- */

    /** Idempotent: stores the pair canonically ordered so a single row
     *  represents the bidirectional relation. A duplicate add is a no-op. */
    void addFriendship(@NotNull UUID a, @NotNull String nameA, @NotNull UUID b, @NotNull String nameB);

    boolean removeFriendship(@NotNull UUID a, @NotNull UUID b);

    boolean areFriends(@NotNull UUID a, @NotNull UUID b);

    /** The other side of every friendship {@code self} is part of. */
    @NotNull List<Friend> friendsOf(@NotNull UUID self);

    int friendCount(@NotNull UUID self);

    /* --- friend requests ------------------------------------------------ */

    /** Creates a PENDING request, or returns the id of the existing pending
     *  one when {@code from → to} is already outstanding. */
    long createFriendRequest(@NotNull UUID from, @NotNull String fromName,
                             @NotNull UUID to, @NotNull String toName);

    @NotNull Optional<FriendRequest> findFriendRequest(long id);

    @NotNull Optional<FriendRequest> findPendingFriendRequest(@NotNull UUID from, @NotNull UUID to);

    @NotNull List<FriendRequest> incomingFriendRequests(@NotNull UUID to);

    @NotNull List<FriendRequest> outgoingFriendRequests(@NotNull UUID from);

    boolean resolveFriendRequest(long id, @NotNull FriendRequest.Status status);

    /* --- parties -------------------------------------------------------- */

    /** Creates an active party and inserts {@code leader} as its LEADER
     *  member in one shot. Returns the new party id. */
    long createParty(@NotNull UUID leaderUuid, @NotNull String leaderName);

    @NotNull Optional<Party> findParty(long id);

    /** The active (not disbanded) party {@code uuid} is a member of, if any. */
    @NotNull Optional<Party> findActivePartyOf(@NotNull UUID uuid);

    @NotNull List<PartyMember> partyMembers(long partyId);

    boolean addPartyMember(long partyId, @NotNull UUID uuid, @NotNull String name, @NotNull String role);

    boolean removePartyMember(long partyId, @NotNull UUID uuid);

    boolean transferPartyLeader(long partyId, @NotNull UUID newLeaderUuid, @NotNull String newLeaderName);

    /** Soft-ends the party (stamps {@code disbanded_at}) and drops its
     *  membership + pending-invite rows. */
    void disbandParty(long partyId);

    /* --- party invites -------------------------------------------------- */

    long createPartyInvite(long partyId, @NotNull UUID from, @NotNull String fromName,
                           @NotNull UUID to, @NotNull String toName, @NotNull Instant expiresAt);

    @NotNull Optional<PartyInvite> findPartyInvite(long id);

    @NotNull Optional<PartyInvite> findPendingPartyInvite(long partyId, @NotNull UUID to);

    /** Pending, not-yet-expired invites addressed to {@code to}. */
    @NotNull List<PartyInvite> incomingPartyInvites(@NotNull UUID to);

    boolean resolvePartyInvite(long id, @NotNull PartyInvite.Status status);

    /** Housekeeping: flips PENDING invites past their expiry to EXPIRED.
     *  Returns the number updated. */
    int expireStalePartyInvites();

    /* --- player preferences --------------------------------------------- */

    /** Never null — returns {@link PlayerPrefs#defaults(UUID)} when no row. */
    @NotNull PlayerPrefs playerPrefs(@NotNull UUID uuid);

    void upsertPlayerPrefs(@NotNull PlayerPrefs prefs);

    /* --- nick sessions (autonicker) ------------------------------------- */

    void startNickSession(@NotNull NickSession session);

    @NotNull Optional<NickSession> findNickSession(@NotNull UUID uuid);

    boolean endNickSession(@NotNull UUID uuid);

    /** Every currently-active nick session — used to restore originals after
     *  a crash/restart. */
    @NotNull List<NickSession> activeNickSessions();
}
