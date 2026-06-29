package de.eternal.bungee.nick;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import de.eternal.bungee.EternalBungee;
import de.eternal.core.config.AutonickerConfig;
import de.eternal.core.integration.CloudPermsAccess;
import de.eternal.core.model.NickSession;
import de.eternal.core.nick.RandomIdentity;
import de.eternal.core.nick.SkinFetcher;
import de.eternal.core.social.SocialStorage;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Proxy-owned nick state for network mode ({@code autonicker.network: true}).
 *
 * <p>The proxy rolls the disguise, fetches the fake skin (it is online-mode and
 * authenticated, so the lookup is reliable), reads the player's REAL skin off
 * their authenticated login profile (for later restore), and then pushes the
 * disguise to whatever backend the player is on — and re-pushes on every server
 * switch. The backend applies it via its reflective profile rewrite, so the
 * skin works network-wide and survives server switches. The real CloudNet group
 * is never touched (stealth — the player keeps their own perms).</p>
 *
 * <p>State is in-memory + a {@link NickSession} row for visibility. A nick lasts
 * for the session: on disconnect (or proxy restart) it is cleared, matching the
 * existing "quit/restart un-nicks" semantics.</p>
 */
public final class NickProxyService {

    private final EternalBungee plugin;
    private final CloudPermsAccess cloudPerms;
    private final SocialStorage storage;
    private final SkinFetcher skinFetcher = new SkinFetcher();
    private final Random random = new Random();
    private final Map<UUID, NickState> states = new ConcurrentHashMap<>();

    /** The full disguise the proxy holds for one player. Skin fields are
     *  nullable (no skin → name + rank only). {@code nickGroup} is the displayed
     *  rank name (empty when none) — used only for the player-facing message. */
    private record NickState(@NotNull String realName, @Nullable String realSkinValue,
                             @Nullable String realSkinSig, @NotNull String fakeName,
                             @Nullable String fakeSkinValue, @Nullable String fakeSkinSig,
                             @NotNull String prefix, @NotNull String suffix, @NotNull String nickGroup) {
    }

    public NickProxyService(@NotNull EternalBungee plugin) {
        this.plugin = plugin;
        this.cloudPerms = plugin.cloudPerms();
        this.storage = (SocialStorage) plugin.storage();
    }

    /** Read live so {@code /eternal reload} takes effect (the service is not
     *  recreated on reload, but the plugin's config object is replaced). */
    private AutonickerConfig config() {
        return plugin.autonicker();
    }

    public boolean isNicked(@NotNull UUID uuid) {
        return states.containsKey(uuid);
    }

    /** The fake name a player is currently nicked as, or null. In-memory (no DB)
     *  — used by tab-completion so the disguise name is suggested. */
    public @Nullable String nickNameOf(@NotNull UUID uuid) {
        NickState st = states.get(uuid);
        return st == null ? null : st.fakeName();
    }

    public void toggle(@NotNull ProxiedPlayer p) {
        if (!config().enabled()) return;
        if (states.containsKey(p.getUniqueId())) unnick(p);
        else nick(p);
    }

    public void set(@NotNull ProxiedPlayer p, boolean on) {
        if (!config().enabled()) return;
        boolean current = states.containsKey(p.getUniqueId());
        if (on && !current) nick(p);
        else if (!on && current) unnick(p);
    }

    private void nick(@NotNull ProxiedPlayer p) {
        UUID u = p.getUniqueId();
        String realName = p.getName();
        String[] realSkin = readRealSkin(p); // main-safe (reflective read of login profile)
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            String originalGroup = "";
            if (cloudPerms.available()) {
                List<String> g = cloudPerms.groupsOf(u);
                if (!g.isEmpty()) originalGroup = g.get(0);
            }
            RandomIdentity id = RandomIdentity.pick(config(), cloudPerms, random);
            SkinFetcher.Skin skin = skinFetcher.fetch(id.skinOwner());
            if (skin == null) {
                plugin.getLogger().warning("Autonick: skin lookup for '" + id.skinOwner()
                        + "' returned null (Mojang unreachable / rate-limited?) — name + rank only.");
            }
            String nickGroup = id.group() == null ? "" : id.group();

            String dd = config().defaultDisplay();
            int idx = dd.indexOf("{name}");
            String fallbackPrefix = idx >= 0 ? dd.substring(0, idx) : dd;
            String fallbackSuffix = idx >= 0 ? dd.substring(idx + "{name}".length()) : "";
            String prefix = id.groupPrefix().isEmpty() ? fallbackPrefix : id.groupPrefix();

            NickState st = new NickState(realName, realSkin[0], realSkin[1], id.name(),
                    skin == null ? null : skin.value(), skin == null ? null : skin.signature(),
                    prefix, fallbackSuffix, nickGroup);
            states.put(u, st);
            // Persist for visibility + stale-cleanup; store the ORIGINAL skin so
            // a standalone restore path stays correct. originalGroup/nickGroup
            // are diagnostics (the real group is never mutated).
            storage.startNickSession(new NickSession(u, realName, originalGroup, id.name(), nickGroup,
                    realSkin[0], realSkin[1], Instant.now()));
            // Doppelrang: ADD the rolled random non-team group (player/premium/…)
            // — the real group + its perms STAY, so CloudNet shows the fake rank's
            // prefix in tab/nametag while the player keeps what they can do.
            if (!nickGroup.isEmpty() && cloudPerms.available()) {
                cloudPerms.addGroup(u, nickGroup);
            }
            sendApply(p, st);
            // Self-view fix: rewrite the forwarded login profile to the FAKE skin
            // and bounce-reconnect so the client reloads its OWN profile (own skin
            // in F5 + own tab head). Without this only OTHERS see the new skin.
            if (config().reconnectOnNick()) {
                writeLoginSkin(p, st.fakeSkinValue(), st.fakeSkinSig());
                bounceReconnect(p);
            }
        });
    }

    private void unnick(@NotNull ProxiedPlayer p) {
        UUID u = p.getUniqueId();
        NickState st = states.remove(u);
        if (st == null) return;
        // Restore the real skin in the login profile up front (for a later
        // reconnect/server-switch).
        if (config().reconnectOnNick()) writeLoginSkin(p, st.realSkinValue(), st.realSkinSig());
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            // Remove the CloudNet nick group FIRST so the backend restore renders
            // the real rank — otherwise CloudNet still shows the nick prefix
            // (e.g. "Premium") on the player's own tab after unnick.
            if (!st.nickGroup().isEmpty() && cloudPerms.available()) cloudPerms.removeGroup(u, st.nickGroup());
            if (storage.findNickSession(u).isPresent()) storage.endNickSession(u);
            sendClear(p, st); // after the group is gone
            if (config().reconnectOnNick()) bounceReconnect(p);
        });
    }

    /** Re-push the active disguise after the player changed backend servers. */
    public void reapplyOnSwitch(@NotNull ProxiedPlayer p) {
        NickState st = states.get(p.getUniqueId());
        if (st != null) sendApply(p, st);
    }

    /** Clear in-memory + persisted state on disconnect (session-scoped nick).
     *  Also drops the Doppelrang display group so a logged-off player isn't left
     *  wearing it. */
    public void handleDisconnect(@NotNull UUID u) {
        NickState st = states.remove(u);
        if (st == null) return;
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            if (!st.nickGroup().isEmpty() && cloudPerms.available()) cloudPerms.removeGroup(u, st.nickGroup());
            if (storage.findNickSession(u).isPresent()) storage.endNickSession(u);
        });
    }

    /* ----------------------------------------------------------------- */

    private void sendApply(@NotNull ProxiedPlayer p, @NotNull NickState st) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("apply");
        out.writeUTF(p.getUniqueId().toString());
        out.writeUTF(st.fakeName());
        out.writeUTF(nullToEmpty(st.fakeSkinValue()));
        out.writeUTF(nullToEmpty(st.fakeSkinSig()));
        out.writeUTF(st.prefix());
        out.writeUTF(st.suffix());
        out.writeUTF(st.nickGroup()); // "" = name only; non-empty → "nick-enabled" with rank
        sendToBackend(p, out.toByteArray());
    }

    private void sendClear(@NotNull ProxiedPlayer p, @NotNull NickState st) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("clear");
        out.writeUTF(p.getUniqueId().toString());
        out.writeUTF(st.realName());
        out.writeUTF(nullToEmpty(st.realSkinValue()));
        out.writeUTF(nullToEmpty(st.realSkinSig()));
        sendToBackend(p, out.toByteArray());
    }

    /** Send to the player's current backend. Captures the server reference once
     *  (avoids a check-then-use race if the player disconnects). Callers are
     *  already off the proxy main path; {@code sendData} is itself thread-safe
     *  and queues the message so it survives a still-connecting backend. */
    private void sendToBackend(@NotNull ProxiedPlayer p, byte[] data) {
        Server server = p.getServer();
        if (server == null) return;
        server.getInfo().sendData(NickProxyListener.CHANNEL, data, true);
    }

    /** Reads the player's authenticated skin texture (value, signature) from
     *  their BungeeCord login profile. Reflective — no compile-time dependency
     *  on the internal {@code LoginResult}/{@code Property} types, and a no-op
     *  ({@code [null,null]}) on an offline-mode proxy where no profile exists. */
    private @NotNull String[] readRealSkin(@NotNull ProxiedPlayer p) {
        try {
            Object pc = p.getPendingConnection();
            Object loginResult = pc.getClass().getMethod("getLoginProfile").invoke(pc);
            if (loginResult == null) return new String[]{null, null};
            Object props = loginResult.getClass().getMethod("getProperties").invoke(loginResult);
            if (props instanceof Object[] arr) {
                for (Object prop : arr) {
                    String name = String.valueOf(prop.getClass().getMethod("getName").invoke(prop));
                    if (!"textures".equals(name)) continue;
                    Object value = prop.getClass().getMethod("getValue").invoke(prop);
                    Object sig = prop.getClass().getMethod("getSignature").invoke(prop);
                    return new String[]{value == null ? null : String.valueOf(value),
                            sig == null ? null : String.valueOf(sig)};
                }
            }
        } catch (Throwable ignored) {
            // offline-mode proxy or API drift — restore degrades to name-only.
        }
        return new String[]{null, null};
    }

    private static @NotNull String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    /** Rewrites the textures property on the player's BungeeCord login profile so
     *  every (re)connect forwards the given skin. Reflective — no compile-time
     *  dependency on the internal {@code LoginResult}/{@code Property}. No-op on an
     *  offline-mode proxy (no profile). {@code value} null/empty → no skin. */
    private void writeLoginSkin(@NotNull ProxiedPlayer p, @Nullable String value, @Nullable String sig) {
        try {
            Object pc = p.getPendingConnection();
            Object lr = pc.getClass().getMethod("getLoginProfile").invoke(pc);
            if (lr == null) return; // offline-mode proxy — nothing to rewrite
            Class<?> propClass = Class.forName("net.md_5.bungee.protocol.Property");
            List<Object> props = new ArrayList<>();
            Object existing = lr.getClass().getMethod("getProperties").invoke(lr);
            if (existing instanceof Object[] arr) {
                for (Object pr : arr) {
                    String n = String.valueOf(pr.getClass().getMethod("getName").invoke(pr));
                    if (!"textures".equals(n)) props.add(pr); // keep non-skin props
                }
            }
            if (value != null && !value.isEmpty()) {
                props.add(propClass.getConstructor(String.class, String.class, String.class)
                        .newInstance("textures", value, sig == null ? "" : sig));
            }
            Object arr = Array.newInstance(propClass, props.size());
            for (int i = 0; i < props.size(); i++) Array.set(arr, i, props.get(i));
            lr.getClass().getMethod("setProperties", arr.getClass()).invoke(lr, arr);
        } catch (Throwable t) {
            plugin.getLogger().warning("Autonick: LoginResult-Skin-Rewrite fehlgeschlagen ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + ").");
        }
    }

    /** Bounces the player through another server and straight back, forcing the
     *  client to re-receive its own profile (Bungee refuses a reconnect to the
     *  SAME server). No-op when no other server exists. */
    private void bounceReconnect(@NotNull ProxiedPlayer p) {
        Server cur = p.getServer();
        if (cur == null) return;
        String curName = cur.getInfo().getName();
        ServerInfo hub = pickBounce(curName);
        if (hub == null) {
            plugin.getLogger().info("Autonick: kein zweiter Server zum Bouncen — Eigen-Skin "
                    + "aktualisiert sich erst beim naechsten Serverwechsel.");
            return;
        }
        p.connect(hub);
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            if (!p.isConnected()) return;
            ServerInfo back = ProxyServer.getInstance().getServerInfo(curName);
            if (back != null) p.connect(back);
        }, 1500, TimeUnit.MILLISECONDS);
    }

    /** Picks a server to bounce through: a lobby/hub/fallback if one exists,
     *  else any server that isn't the player's current one. */
    private @Nullable ServerInfo pickBounce(@NotNull String currentName) {
        ServerInfo any = null;
        for (ServerInfo si : ProxyServer.getInstance().getServers().values()) {
            if (si.getName().equalsIgnoreCase(currentName)) continue;
            String n = si.getName().toLowerCase();
            if (n.contains("lobby") || n.contains("hub") || n.contains("fallback")) return si;
            if (any == null) any = si;
        }
        return any;
    }
}
