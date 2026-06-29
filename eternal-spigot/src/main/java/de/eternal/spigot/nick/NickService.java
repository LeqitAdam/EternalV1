package de.eternal.spigot.nick;

import de.eternal.core.config.AutonickerConfig;
import de.eternal.core.integration.CloudPermsAccess;
import de.eternal.core.model.NickSession;
import de.eternal.core.nick.RandomIdentity;
import de.eternal.core.nick.SkinFetcher;
import de.eternal.core.social.SocialStorage;
import de.eternal.core.text.MessageBank;
import de.eternal.spigot.EternalSpigot;
import de.eternal.spigot.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Toggles a player's disguise. Two modes, chosen by {@code autonicker.network}:
 *
 * <ul>
 *   <li><b>standalone</b> (network=false): this Spigot server rolls the
 *       identity, fetches the skin, persists the {@link NickSession} and applies
 *       the disguise locally — the original single-server behaviour.</li>
 *   <li><b>network</b> (network=true): the BungeeCord proxy owns nick state. A
 *       toggle here just asks the proxy (plugin message); the proxy rolls +
 *       persists, then pushes {@code applyDisguise}/{@code restore} back to
 *       whatever backend the player is on (and re-pushes on every server
 *       switch), so the skin works network-wide and survives switches.</li>
 * </ul>
 *
 * <p>The disguise never changes the player's real CloudNet group, so a nicked
 * player keeps their own permissions (stealth). Config/messages are read live
 * from the plugin so {@code /eternal reload} takes effect without a restart.</p>
 */
public final class NickService {

    private final EternalSpigot plugin;
    private final NickApplier applier;
    private final SkinFetcher skinFetcher = new SkinFetcher();
    private final Random random = new Random();
    /** uuid → current fake name (also the "is nicked" set). */
    private final Map<UUID, String> nicked = new ConcurrentHashMap<>();

    public NickService(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
        this.applier = new NickApplier(plugin);
    }

    private AutonickerConfig config() { return plugin.autonicker(); }
    private Messages messages() { return plugin.messages(); }
    private CloudPermsAccess cloudPerms() { return plugin.cloudPerms(); }
    private SocialStorage storage() { return (SocialStorage) plugin.storage(); }

    /** The fake name a player is currently nicked as, or null. Used by the chat
     *  rewrite so the chat name matches the disguise. */
    public @Nullable String nickNameOf(@NotNull UUID uuid) {
        return nicked.get(uuid);
    }

    /** The online player currently nicked AS {@code name} (case-insensitive), or
     *  null. Lets commands resolve a disguised player by the fake name everyone
     *  sees (/msg, /tp, …). Same-server only — the local nick map. */
    public @Nullable Player playerByNickName(@NotNull String name) {
        for (Map.Entry<UUID, String> e : nicked.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) return p;
            }
        }
        return null;
    }

    public void giveTag(@NotNull Player p) {
        if (!config().enabled()) return;
        p.getInventory().setItem(config().nametagSlot(), NickItems.tag(plugin, nicked.containsKey(p.getUniqueId())));
    }

    /** Removes ANY nick item from the player's inventory — used to clean up a
     *  leftover item from a player who no longer has the nick permission (e.g.
     *  it was handed out before the perm gate existed). */
    public void removeTag(@NotNull Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (NickItems.isTag(plugin, inv.getItem(i))) inv.setItem(i, null);
        }
    }

    public boolean isNicked(@NotNull UUID uuid) {
        return nicked.containsKey(uuid);
    }

    public void toggle(@NotNull Player p) {
        if (!config().enabled()) {
            messages().send(p, "nick-disabled-feature");
            return;
        }
        if (config().network()) {
            plugin.nickBridge().requestToggle(p);
            return;
        }
        if (nicked.containsKey(p.getUniqueId())) unnickLocal(p);
        else nickLocal(p);
    }

    public void setNick(@NotNull Player p, boolean on) {
        if (!config().enabled()) {
            messages().send(p, "nick-disabled-feature");
            return;
        }
        if (config().network()) {
            plugin.nickBridge().requestSet(p, on);
            return;
        }
        boolean current = nicked.containsKey(p.getUniqueId());
        if (on && !current) nickLocal(p);
        else if (!on && current) unnickLocal(p);
    }

    /* ----------------------------------------------------------------- */
    /* Network mode — applied on instruction from the proxy. Plugin       */
    /* messages arrive on the main thread, so Bukkit calls are safe here. */
    /* ----------------------------------------------------------------- */

    /** Apply a proxy-rolled disguise. Skin args may be null (name + rank only);
     *  {@code nickGroup} is the displayed rank name ("" → name only). */
    public void applyDisguise(@NotNull Player p, @NotNull String fakeName,
                              @Nullable String skinValue, @Nullable String skinSignature,
                              @NotNull String coloredPrefix, @NotNull String coloredSuffix,
                              @NotNull String nickGroup) {
        // Tab name + team are set AFTER the re-track (callback) so the client
        // reliably colours the new name — no more "white until someone else nicks".
        boolean ok = applier.apply(p, fakeName, skinValue, skinSignature, () -> {
            p.setPlayerListName(MessageBank.colorize(NickTab.nameColorCode(coloredPrefix) + fakeName));
            NickTab.apply(p, fakeName, coloredPrefix, coloredSuffix);
        });
        p.setDisplayName(MessageBank.colorize(coloredPrefix + fakeName + coloredSuffix));
        nicked.put(p.getUniqueId(), fakeName);
        giveTag(p);
        if (!ok) messages().send(p, "nick-failed");
        else if (nickGroup.isEmpty()) messages().send(p, "nick-enabled-name-only", "name", fakeName);
        else messages().send(p, "nick-enabled", "name", fakeName, "rank", nickGroup);
    }

    /** Restore the real identity on instruction from the proxy. */
    public void restore(@NotNull Player p, @NotNull String realName,
                        @Nullable String realSkinValue, @Nullable String realSkinSignature) {
        // Clear the tab name + team AFTER the re-track so the real name shows
        // deterministically (no stale nick name in the own tab).
        applier.apply(p, realName, realSkinValue, realSkinSignature, () -> {
            p.setPlayerListName(null);
            NickTab.clear(p.getUniqueId());
        });
        p.setDisplayName(realName);
        nicked.remove(p.getUniqueId());
        giveTag(p);
        messages().send(p, "nick-disabled", "name", realName);
    }

    /* ----------------------------------------------------------------- */
    /* Standalone mode — this server rolls + persists + applies.          */
    /* ----------------------------------------------------------------- */

    private void nickLocal(@NotNull Player p) {
        UUID u = p.getUniqueId();
        String realName = p.getName();
        String[] origSkin = applier.currentTextures(p); // read on the main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            AutonickerConfig config = config();
            CloudPermsAccess cloudPerms = cloudPerms();
            // Informational only — we never mutate the real CloudNet group, so
            // the player keeps their own permissions while nicked (stealth).
            String originalGroup = "";
            if (cloudPerms.available()) {
                List<String> g = cloudPerms.groupsOf(u);
                if (!g.isEmpty()) originalGroup = g.get(0);
            }
            RandomIdentity id = RandomIdentity.pick(config, cloudPerms, random);
            SkinFetcher.Skin nickSkin = skinFetcher.fetch(id.skinOwner());
            if (nickSkin == null) {
                plugin.getLogger().warning("Autonick: skin lookup for '" + id.skinOwner()
                        + "' returned null (Mojang unreachable / rate-limited?) — name + rank only.");
            }
            String nickGroup = id.group() == null ? "" : id.group();
            storage().startNickSession(new NickSession(u, realName, originalGroup, id.name(), nickGroup,
                    origSkin == null ? null : origSkin[0], origSkin == null ? null : origSkin[1], Instant.now()));
            String dd = config.defaultDisplay();
            int idx = dd.indexOf("{name}");
            String fallbackPrefix = idx >= 0 ? dd.substring(0, idx) : dd;
            String fallbackSuffix = idx >= 0 ? dd.substring(idx + "{name}".length()) : "";
            String tabPrefix = id.groupPrefix().isEmpty() ? fallbackPrefix : id.groupPrefix();
            String chatDisplay = MessageBank.colorize(tabPrefix + id.name() + fallbackSuffix);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                boolean ok = applier.apply(p, id.name(),
                        nickSkin == null ? null : nickSkin.value(),
                        nickSkin == null ? null : nickSkin.signature(),
                        () -> {
                            p.setPlayerListName(MessageBank.colorize(NickTab.nameColorCode(tabPrefix) + id.name()));
                            NickTab.apply(p, id.name(), tabPrefix, fallbackSuffix);
                        });
                p.setDisplayName(chatDisplay);
                nicked.put(u, id.name());
                giveTag(p);
                if (!ok) {
                    messages().send(p, "nick-failed");
                    return;
                }
                if (!nickGroup.isEmpty()) {
                    messages().send(p, "nick-enabled", "name", id.name(), "rank", nickGroup);
                } else {
                    messages().send(p, "nick-enabled-name-only", "name", id.name());
                    if (!cloudPerms.available() && config.hideRankWithoutCloudnet()) {
                        messages().send(p, "nick-no-cloudnet");
                    }
                }
            });
        });
    }

    private void unnickLocal(@NotNull Player p) {
        UUID u = p.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<NickSession> opt = storage().findNickSession(u);
            String originalName = opt.map(NickSession::originalName).orElse(p.getName());
            String[] origSkin = opt.map(s -> new String[]{s.skinValue(), s.skinSignature()}).orElse(null);
            if (opt.isPresent()) storage().endNickSession(u);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    applier.apply(p, originalName, origSkin == null ? null : origSkin[0],
                            origSkin == null ? null : origSkin[1],
                            () -> { p.setPlayerListName(null); NickTab.clear(u); });
                    p.setDisplayName(originalName);
                } else {
                    NickTab.clear(u);
                }
                nicked.remove(u);
                giveTag(p);
                messages().send(p, "nick-disabled", "name", originalName);
            });
        });
    }

    /** On quit, drop local state + the disguise team. In standalone mode also
     *  end the persisted session; in network mode the proxy owns that. */
    public void handleQuit(@NotNull UUID u) {
        boolean wasNicked = nicked.remove(u) != null;
        NickTab.clear(u); // quit fires on the main thread — scoreboard edit is safe
        if (!config().network() && wasNicked) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (storage().findNickSession(u).isPresent()) storage().endNickSession(u);
            });
        }
    }
}
