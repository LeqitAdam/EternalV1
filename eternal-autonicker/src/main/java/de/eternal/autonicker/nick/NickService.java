package de.eternal.autonicker.nick;

import de.eternal.core.config.AutonickerConfig;
import de.eternal.core.integration.CloudPermsAccess;
import de.eternal.core.model.NickSession;
import de.eternal.core.social.SocialStorage;
import de.eternal.core.text.MessageBank;
import de.eternal.autonicker.EternalAutonicker;
import de.eternal.autonicker.Messages;
import de.eternal.autonicker.item.NickItems;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Toggles a player's disguise. DB + CloudNet work runs async; the actual
 * profile rewrite + chat I/O happen back on the main thread. The nick session
 * is persisted BEFORE the destructive CloudNet group change so a crash can be
 * recovered (see {@code EternalAutonicker#restoreDanglingSessions}).
 */
public final class NickService {

    private final EternalAutonicker plugin;
    private final SocialStorage storage;
    private final AutonickerConfig config;
    private final Messages messages;
    private final CloudPermsAccess cloudPerms;
    private final NickApplier applier;
    private final SkinFetcher skinFetcher = new SkinFetcher();
    private final Random random = new Random();
    private final Set<UUID> nicked = ConcurrentHashMap.newKeySet();

    public NickService(@NotNull EternalAutonicker plugin) {
        this.plugin = plugin;
        this.storage = plugin.storage();
        this.config = plugin.config();
        this.messages = plugin.messages();
        this.cloudPerms = plugin.cloudPerms();
        this.applier = new NickApplier(plugin);
    }

    public void giveTag(@NotNull Player p) {
        if (!config.enabled()) return;
        p.getInventory().setItem(config.nametagSlot(), NickItems.tag(plugin, nicked.contains(p.getUniqueId())));
    }

    public boolean isNicked(@NotNull UUID uuid) {
        return nicked.contains(uuid);
    }

    public void toggle(@NotNull Player p) {
        if (!config.enabled()) {
            messages.send(p, "nick-disabled-feature");
            return;
        }
        if (nicked.contains(p.getUniqueId())) unnick(p);
        else nick(p);
    }

    public void setNick(@NotNull Player p, boolean on) {
        if (!config.enabled()) {
            messages.send(p, "nick-disabled-feature");
            return;
        }
        boolean current = nicked.contains(p.getUniqueId());
        if (on && !current) nick(p);
        else if (!on && current) unnick(p);
    }

    private void nick(@NotNull Player p) {
        UUID u = p.getUniqueId();
        String realName = p.getName();
        String[] origSkin = applier.currentTextures(p); // read on the main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String originalGroup = "";
            if (cloudPerms.available()) {
                List<String> g = cloudPerms.groupsOf(u);
                if (!g.isEmpty()) originalGroup = g.get(0);
            }
            RandomIdentity id = RandomIdentity.pick(config, cloudPerms, random);
            SkinFetcher.Skin nickSkin = skinFetcher.fetch(id.skinOwner());
            String nickGroup = "";
            if (config.randomizeRank() && cloudPerms.available() && id.group() != null) {
                if (cloudPerms.setPrimaryGroup(u, id.group())) nickGroup = id.group();
            }
            // Persist BEFORE the visuals so a crash mid-nick is recoverable.
            storage.startNickSession(new NickSession(u, realName, originalGroup, id.name(), nickGroup,
                    origSkin == null ? null : origSkin[0], origSkin == null ? null : origSkin[1], Instant.now()));
            final String fGroup = nickGroup;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                boolean ok = applier.apply(p, id.name(),
                        nickSkin == null ? null : nickSkin.value(),
                        nickSkin == null ? null : nickSkin.signature());
                String display = MessageBank.colorize(config.defaultDisplay().replace("{name}", id.name()));
                p.setDisplayName(display);
                p.setPlayerListName(display);
                nicked.add(u);
                giveTag(p);
                if (!ok) {
                    messages.send(p, "nick-failed");
                    return;
                }
                if (cloudPerms.available() && !fGroup.isEmpty()) {
                    messages.send(p, "nick-enabled", "name", id.name(), "rank", fGroup);
                } else if (!cloudPerms.available()) {
                    messages.send(p, "nick-enabled-name-only", "name", id.name());
                    if (config.hideRankWithoutCloudnet()) messages.send(p, "nick-no-cloudnet");
                } else {
                    messages.send(p, "nick-enabled-name-only", "name", id.name());
                }
            });
        });
    }

    private void unnick(@NotNull Player p) {
        UUID u = p.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<NickSession> opt = storage.findNickSession(u);
            String originalName = opt.map(NickSession::originalName).orElse(p.getName());
            String[] origSkin = opt.map(s -> new String[]{s.skinValue(), s.skinSignature()}).orElse(null);
            if (opt.isPresent()) {
                NickSession s = opt.get();
                if (cloudPerms.available() && !s.originalGroup().isEmpty()) {
                    cloudPerms.setPrimaryGroup(u, s.originalGroup());
                }
                storage.endNickSession(u);
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    applier.apply(p, originalName, origSkin == null ? null : origSkin[0],
                            origSkin == null ? null : origSkin[1]);
                    p.setDisplayName(originalName);
                    p.setPlayerListName(null);
                }
                nicked.remove(u);
                giveTag(p);
                messages.send(p, "nick-disabled", "name", originalName);
            });
        });
    }

    /** On quit, undo any active nick centrally (restore the real CloudNet group
     *  + clear the session) so a logged-off player is never left wearing a
     *  random rank persistently. */
    public void handleQuit(@NotNull UUID u) {
        nicked.remove(u);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<NickSession> opt = storage.findNickSession(u);
            if (opt.isPresent()) {
                NickSession s = opt.get();
                if (cloudPerms.available() && !s.originalGroup().isEmpty()) {
                    cloudPerms.setPrimaryGroup(u, s.originalGroup());
                }
                storage.endNickSession(u);
            }
        });
    }
}
