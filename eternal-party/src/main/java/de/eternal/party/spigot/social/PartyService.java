package de.eternal.party.spigot.social;

import de.eternal.core.config.PartyConfig;
import de.eternal.core.model.Friend;
import de.eternal.core.model.FriendRequest;
import de.eternal.core.model.Party;
import de.eternal.core.model.PartyInvite;
import de.eternal.core.model.PartyMember;
import de.eternal.core.model.PlayerPrefs;
import de.eternal.core.model.PlayerProfile;
import de.eternal.core.social.SocialStorage;
import de.eternal.party.spigot.EternalPartySpigot;
import de.eternal.party.spigot.Messages;
import de.eternal.party.spigot.gui.FriendsGui;
import de.eternal.party.spigot.gui.MembersGui;
import de.eternal.party.spigot.gui.PartyGui;
import de.eternal.party.spigot.gui.SettingsGui;
import de.eternal.party.spigot.item.PartyItems;
import de.eternal.party.spigot.net.Notifications;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Central party + friends logic. Every public entry point is called on the main
 * thread; all DB work is bounced to the async scheduler and any player-facing
 * I/O (chat, GUIs, plugin messages) is bounced back to the main thread via
 * {@link #sync}. The hit-the-player-with-the-head flow lives in
 * {@link #headInteract}.
 */
public final class PartyService {

    private final EternalPartySpigot plugin;
    private final SocialStorage storage;
    private final Messages messages;
    private final PartyConfig config;
    private final Notifications notifications;

    public PartyService(@NotNull EternalPartySpigot plugin) {
        this.plugin = plugin;
        this.storage = plugin.storage();
        this.messages = plugin.messages();
        this.config = plugin.partyConfig();
        this.notifications = new Notifications(plugin);
    }

    /* --- items -------------------------------------------------------- */

    public void giveHead(@NotNull Player p) {
        if (!config.enabled()) return;
        p.getInventory().setItem(config.headSlot(), PartyItems.head(plugin, p));
    }

    /* --- the headline flow: hit a player with the head ---------------- */

    /** Left/right interaction with another player while holding the head:
     *  friend → party invite, stranger → friend request (both gated on the
     *  target's preferences). */
    public void headInteract(@NotNull Player attacker, @NotNull Player target) {
        if (attacker.getUniqueId().equals(target.getUniqueId())) {
            messages.send(attacker, "party-self");
            return;
        }
        UUID a = attacker.getUniqueId();
        UUID t = target.getUniqueId();
        String tName = target.getName();
        boolean bypass = attacker.hasPermission("eternal.party.bypass-invite-settings");
        async(() -> {
            boolean friends = storage.areFriends(a, t);
            PlayerPrefs prefs = storage.playerPrefs(t);
            if (friends) {
                if (!prefs.allowPartyInvites() && !bypass) {
                    sync(() -> messages.send(attacker, "party-invite-disabled", "target", tName));
                    return;
                }
                doInvite(attacker, t, tName);
            } else {
                if (!prefs.allowFriendRequests()) {
                    sync(() -> messages.send(attacker, "friend-request-disabled", "target", tName));
                    return;
                }
                doFriendRequest(attacker, t, tName);
            }
        });
    }

    /* --- menus -------------------------------------------------------- */

    public void openMenu(@NotNull Player p) {
        UUID u = p.getUniqueId();
        async(() -> {
            Optional<Party> party = storage.findActivePartyOf(u);
            int count = party.map(pt -> storage.partyMembers(pt.id()).size()).orElse(0);
            boolean leader = party.map(pt -> pt.leaderUuid().equals(u)).orElse(false);
            String leaderName = party.map(Party::leaderName).orElse("");
            sync(() -> PartyGui.open(plugin, p, party.isPresent(), leader, leaderName, count));
        });
    }

    public void openFriends(@NotNull Player p) {
        async(() -> {
            List<Friend> friends = storage.friendsOf(p.getUniqueId());
            sync(() -> FriendsGui.open(plugin, p, friends));
        });
    }

    public void openMembers(@NotNull Player p) {
        async(() -> {
            Optional<Party> party = storage.findActivePartyOf(p.getUniqueId());
            if (party.isEmpty()) {
                sync(() -> messages.send(p, "party-not-in-party"));
                return;
            }
            List<PartyMember> members = storage.partyMembers(party.get().id());
            sync(() -> MembersGui.open(plugin, p, members));
        });
    }

    public void openSettings(@NotNull Player p) {
        async(() -> {
            PlayerPrefs prefs = storage.playerPrefs(p.getUniqueId());
            sync(() -> SettingsGui.open(plugin, p, prefs));
        });
    }

    public void toggleSetting(@NotNull Player p, boolean friendRequests) {
        async(() -> {
            PlayerPrefs prefs = storage.playerPrefs(p.getUniqueId());
            PlayerPrefs updated = friendRequests
                    ? prefs.withAllowFriendRequests(!prefs.allowFriendRequests())
                    : prefs.withAllowPartyInvites(!prefs.allowPartyInvites());
            storage.upsertPlayerPrefs(updated);
            sync(() -> SettingsGui.open(plugin, p, updated));
        });
    }

    /* --- party actions ------------------------------------------------ */

    public void createParty(@NotNull Player p) {
        async(() -> {
            if (storage.findActivePartyOf(p.getUniqueId()).isPresent()) {
                sync(() -> messages.send(p, "party-already-in"));
                return;
            }
            storage.createParty(p.getUniqueId(), p.getName());
            sync(() -> messages.send(p, "party-created"));
        });
    }

    public void inviteByName(@NotNull Player inviter, @NotNull String name) {
        Player online = Bukkit.getPlayerExact(name);
        async(() -> {
            UUID tu;
            String tName;
            if (online != null) {
                tu = online.getUniqueId();
                tName = online.getName();
            } else {
                Optional<PlayerProfile> prof = plugin.profiles().findProfileByName(name);
                if (prof.isEmpty()) {
                    sync(() -> messages.send(inviter, "player-not-found"));
                    return;
                }
                tu = prof.get().uuid();
                tName = prof.get().name();
            }
            if (tu.equals(inviter.getUniqueId())) {
                sync(() -> messages.send(inviter, "party-invite-accept-self"));
                return;
            }
            PlayerPrefs prefs = storage.playerPrefs(tu);
            if (!prefs.allowPartyInvites() && !inviter.hasPermission("eternal.party.bypass-invite-settings")) {
                String fn = tName;
                sync(() -> messages.send(inviter, "party-invite-disabled", "target", fn));
                return;
            }
            doInvite(inviter, tu, tName);
        });
    }

    /** Party-invite a known friend straight from the friends GUI. */
    public void inviteFriend(@NotNull Player inviter, @NotNull UUID targetUuid) {
        async(() -> {
            String tName = resolveName(targetUuid);
            PlayerPrefs prefs = storage.playerPrefs(targetUuid);
            if (!prefs.allowPartyInvites() && !inviter.hasPermission("eternal.party.bypass-invite-settings")) {
                sync(() -> messages.send(inviter, "party-invite-disabled", "target", tName));
                return;
            }
            doInvite(inviter, targetUuid, tName);
        });
    }

    /** Shared invite body — runs in async context. Creates the inviter's party
     *  on demand and notifies both sides. */
    private void doInvite(@NotNull Player inviter, @NotNull UUID targetUuid, @NotNull String targetName) {
        UUID iu = inviter.getUniqueId();
        String iName = inviter.getName();
        if (storage.findActivePartyOf(targetUuid).isPresent()) {
            sync(() -> messages.send(inviter, "party-target-in-party", "target", targetName));
            return;
        }
        Optional<Party> existing = storage.findActivePartyOf(iu);
        long partyId;
        boolean created = false;
        if (existing.isEmpty()) {
            partyId = storage.createParty(iu, iName);
            created = true;
        } else {
            partyId = existing.get().id();
            if (storage.partyMembers(partyId).size() >= config.maxMembers()) {
                sync(() -> messages.send(inviter, "party-full"));
                return;
            }
        }
        long expiresAt = System.currentTimeMillis() + config.inviteExpirySeconds() * 1000L;
        long inviteId = storage.createPartyInvite(partyId, iu, iName, targetUuid, targetName, Instant.ofEpochMilli(expiresAt));
        boolean wasCreated = created;
        sync(() -> {
            if (wasCreated) messages.send(inviter, "party-created");
            messages.send(inviter, "party-invite-sent", "target", targetName);
            notifications.notifyClickable(targetUuid,
                    messages.format("party-invite-received", "inviter", iName),
                    "/party accept " + inviteId);
        });
    }

    public void acceptInvite(@NotNull Player p, long inviteId) {
        async(() -> acceptInviteNow(p, inviteId));
    }

    public void acceptNewestInvite(@NotNull Player p) {
        async(() -> {
            List<PartyInvite> list = storage.incomingPartyInvites(p.getUniqueId());
            if (list.isEmpty()) {
                sync(() -> messages.send(p, "party-invite-none"));
                return;
            }
            acceptInviteNow(p, list.get(0).id());
        });
    }

    private void acceptInviteNow(@NotNull Player p, long inviteId) {
        Optional<PartyInvite> opt = storage.findPartyInvite(inviteId);
        if (opt.isEmpty() || opt.get().status() != PartyInvite.Status.PENDING
                || !opt.get().toUuid().equals(p.getUniqueId())) {
            sync(() -> messages.send(p, "party-invite-none"));
            return;
        }
        PartyInvite invite = opt.get();
        if (invite.expiresAt() != null && invite.expiresAt().toEpochMilli() < System.currentTimeMillis()) {
            storage.resolvePartyInvite(inviteId, PartyInvite.Status.EXPIRED);
            sync(() -> messages.send(p, "party-invite-expired"));
            return;
        }
        Optional<Party> party = storage.findParty(invite.partyId());
        if (party.isEmpty() || !party.get().active()) {
            storage.resolvePartyInvite(inviteId, PartyInvite.Status.CANCELLED);
            sync(() -> messages.send(p, "party-invite-expired"));
            return;
        }
        if (storage.partyMembers(invite.partyId()).size() >= config.maxMembers()) {
            sync(() -> messages.send(p, "party-full"));
            return;
        }
        // Leave any current party before joining the new one.
        storage.findActivePartyOf(p.getUniqueId())
                .ifPresent(cur -> leaveInternal(p.getUniqueId(), p.getName(), cur));
        storage.resolvePartyInvite(inviteId, PartyInvite.Status.ACCEPTED);
        storage.addPartyMember(invite.partyId(), p.getUniqueId(), p.getName(), PartyMember.ROLE_MEMBER);
        List<PartyMember> after = storage.partyMembers(invite.partyId());
        String joinMsg = messages.format("party-join-broadcast", "player", p.getName());
        sync(() -> {
            for (PartyMember m : after) notifications.notify(m.uuid(), joinMsg);
        });
    }

    public void denyNewestInvite(@NotNull Player p) {
        async(() -> {
            List<PartyInvite> list = storage.incomingPartyInvites(p.getUniqueId());
            if (list.isEmpty()) {
                sync(() -> messages.send(p, "party-invite-none"));
                return;
            }
            storage.resolvePartyInvite(list.get(0).id(), PartyInvite.Status.DECLINED);
            sync(() -> messages.send(p, "party-invite-denied"));
        });
    }

    public void leave(@NotNull Player p) {
        async(() -> {
            Optional<Party> party = storage.findActivePartyOf(p.getUniqueId());
            if (party.isEmpty()) {
                sync(() -> messages.send(p, "party-not-in-party"));
                return;
            }
            leaveInternal(p.getUniqueId(), p.getName(), party.get());
            sync(() -> messages.send(p, "party-left"));
        });
    }

    private void leaveInternal(@NotNull UUID u, @NotNull String name, @NotNull Party party) {
        storage.removePartyMember(party.id(), u);
        List<PartyMember> remaining = storage.partyMembers(party.id());
        String leaveMsg = messages.format("party-leave-broadcast", "player", name);
        if (remaining.isEmpty()) {
            storage.disbandParty(party.id());
            return;
        }
        if (party.leaderUuid().equals(u)) {
            PartyMember next = remaining.get(0);
            storage.transferPartyLeader(party.id(), next.uuid(), next.name());
            String transferMsg = messages.format("party-leader-transferred", "player", next.name());
            sync(() -> {
                for (PartyMember m : remaining) {
                    notifications.notify(m.uuid(), leaveMsg);
                    notifications.notify(m.uuid(), transferMsg);
                }
            });
        } else {
            sync(() -> {
                for (PartyMember m : remaining) notifications.notify(m.uuid(), leaveMsg);
            });
        }
    }

    public void disband(@NotNull Player p) {
        async(() -> {
            Optional<Party> party = storage.findActivePartyOf(p.getUniqueId());
            if (party.isEmpty()) {
                sync(() -> messages.send(p, "party-not-in-party"));
                return;
            }
            if (!party.get().leaderUuid().equals(p.getUniqueId())) {
                sync(() -> messages.send(p, "party-only-leader"));
                return;
            }
            List<PartyMember> members = storage.partyMembers(party.get().id());
            storage.disbandParty(party.get().id());
            String msg = messages.format("party-disbanded");
            sync(() -> {
                for (PartyMember m : members) notifications.notify(m.uuid(), msg);
            });
        });
    }

    public void kick(@NotNull Player leader, @NotNull String targetName) {
        async(() -> {
            Optional<Party> party = storage.findActivePartyOf(leader.getUniqueId());
            if (party.isEmpty()) {
                sync(() -> messages.send(leader, "party-not-in-party"));
                return;
            }
            if (!party.get().leaderUuid().equals(leader.getUniqueId())) {
                sync(() -> messages.send(leader, "party-only-leader"));
                return;
            }
            Optional<PartyMember> target = storage.partyMembers(party.get().id()).stream()
                    .filter(m -> m.name().equalsIgnoreCase(targetName)).findFirst();
            if (target.isEmpty()) {
                sync(() -> messages.send(leader, "party-member-not-found"));
                return;
            }
            if (target.get().uuid().equals(leader.getUniqueId())) {
                sync(() -> messages.send(leader, "party-self"));
                return;
            }
            storage.removePartyMember(party.get().id(), target.get().uuid());
            String kicked = messages.format("party-kicked-you");
            String broadcast = messages.format("party-kick-broadcast", "player", target.get().name());
            List<PartyMember> remaining = storage.partyMembers(party.get().id());
            UUID kickedUuid = target.get().uuid();
            sync(() -> {
                notifications.notify(kickedUuid, kicked);
                for (PartyMember m : remaining) notifications.notify(m.uuid(), broadcast);
            });
        });
    }

    public void list(@NotNull Player p) {
        async(() -> {
            Optional<Party> party = storage.findActivePartyOf(p.getUniqueId());
            if (party.isEmpty()) {
                sync(() -> messages.send(p, "party-not-in-party"));
                return;
            }
            List<PartyMember> members = storage.partyMembers(party.get().id());
            sync(() -> {
                messages.send(p, "party-list-header", "count", members.size(), "max", config.maxMembers());
                for (PartyMember m : members) {
                    String role = m.isLeader() ? "Leader" : "Member";
                    p.sendMessage(messages.format("party-list-entry", "name", m.name(), "role", role));
                }
            });
        });
    }

    /* --- friend actions ----------------------------------------------- */

    private void doFriendRequest(@NotNull Player from, @NotNull UUID targetUuid, @NotNull String targetName) {
        UUID fu = from.getUniqueId();
        String fName = from.getName();
        if (storage.areFriends(fu, targetUuid)) {
            sync(() -> messages.send(from, "friend-already", "name", targetName));
            return;
        }
        storage.createFriendRequest(fu, fName, targetUuid, targetName);
        sync(() -> {
            messages.send(from, "friend-request-sent", "target", targetName);
            notifications.notifyClickable(targetUuid,
                    messages.format("friend-request-received", "sender", fName),
                    "/friend accept " + fName);
        });
    }

    public void addFriendByName(@NotNull Player from, @NotNull String name) {
        Player online = Bukkit.getPlayerExact(name);
        async(() -> {
            UUID tu;
            String tName;
            if (online != null) {
                tu = online.getUniqueId();
                tName = online.getName();
            } else {
                Optional<PlayerProfile> prof = plugin.profiles().findProfileByName(name);
                if (prof.isEmpty()) {
                    sync(() -> messages.send(from, "friend-not-found", "name", name));
                    return;
                }
                tu = prof.get().uuid();
                tName = prof.get().name();
            }
            if (tu.equals(from.getUniqueId())) {
                sync(() -> messages.send(from, "friend-request-self"));
                return;
            }
            if (storage.areFriends(from.getUniqueId(), tu)) {
                String fn = tName;
                sync(() -> messages.send(from, "friend-already", "name", fn));
                return;
            }
            PlayerPrefs prefs = storage.playerPrefs(tu);
            if (!prefs.allowFriendRequests()) {
                String fn = tName;
                sync(() -> messages.send(from, "friend-request-disabled", "target", fn));
                return;
            }
            doFriendRequest(from, tu, tName);
        });
    }

    public void acceptFriendRequest(@NotNull Player p, @NotNull String fromName) {
        async(() -> {
            Optional<FriendRequest> match = storage.incomingFriendRequests(p.getUniqueId()).stream()
                    .filter(r -> r.fromName().equalsIgnoreCase(fromName)).findFirst();
            if (match.isEmpty()) {
                sync(() -> messages.send(p, "friend-request-none"));
                return;
            }
            FriendRequest req = match.get();
            storage.resolveFriendRequest(req.id(), FriendRequest.Status.ACCEPTED);
            storage.addFriendship(req.fromUuid(), req.fromName(), p.getUniqueId(), p.getName());
            String toRequester = messages.format("friend-added", "name", p.getName());
            sync(() -> {
                messages.send(p, "friend-added", "name", req.fromName());
                notifications.notify(req.fromUuid(), toRequester);
            });
        });
    }

    public void denyFriendRequest(@NotNull Player p, @NotNull String fromName) {
        async(() -> {
            Optional<FriendRequest> match = storage.incomingFriendRequests(p.getUniqueId()).stream()
                    .filter(r -> r.fromName().equalsIgnoreCase(fromName)).findFirst();
            if (match.isEmpty()) {
                sync(() -> messages.send(p, "friend-request-none"));
                return;
            }
            storage.resolveFriendRequest(match.get().id(), FriendRequest.Status.DECLINED);
            sync(() -> messages.send(p, "friend-request-denied"));
        });
    }

    public void removeFriendByName(@NotNull Player p, @NotNull String name) {
        async(() -> {
            Optional<Friend> match = storage.friendsOf(p.getUniqueId()).stream()
                    .filter(f -> f.name().equalsIgnoreCase(name)).findFirst();
            if (match.isEmpty()) {
                sync(() -> messages.send(p, "friend-not-found", "name", name));
                return;
            }
            storage.removeFriendship(p.getUniqueId(), match.get().uuid());
            sync(() -> messages.send(p, "friend-removed", "name", match.get().name()));
        });
    }

    /** Remove a friend by UUID + refresh the friends GUI (used by the GUI). */
    public void removeFriendByUuid(@NotNull Player p, @NotNull UUID targetUuid) {
        async(() -> {
            String name = resolveName(targetUuid);
            storage.removeFriendship(p.getUniqueId(), targetUuid);
            List<Friend> friends = storage.friendsOf(p.getUniqueId());
            sync(() -> {
                messages.send(p, "friend-removed", "name", name);
                FriendsGui.open(plugin, p, friends);
            });
        });
    }

    public void friendList(@NotNull Player p) {
        async(() -> {
            List<Friend> friends = storage.friendsOf(p.getUniqueId());
            sync(() -> {
                if (friends.isEmpty()) {
                    messages.send(p, "friend-list-empty");
                    return;
                }
                messages.send(p, "friend-list-header", "count", friends.size());
                for (Friend f : friends) {
                    boolean online = Bukkit.getPlayer(f.uuid()) != null;
                    String status = online ? messages.format("gui-friend-online") : messages.format("gui-friend-offline");
                    p.sendMessage(messages.format("friend-list-entry", "name", f.name(), "status", status));
                }
            });
        });
    }

    /* --- helpers ------------------------------------------------------ */

    private @NotNull String resolveName(@NotNull UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        return plugin.profiles().findProfile(uuid).map(PlayerProfile::name).orElse("?");
    }

    private void async(@NotNull Runnable r) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
    }

    private void sync(@NotNull Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    @SuppressWarnings("unused")
    private @Nullable Player online(@NotNull UUID uuid) {
        return Bukkit.getPlayer(uuid);
    }
}
