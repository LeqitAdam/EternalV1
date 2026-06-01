package de.eternal.spigot.consent;

import de.eternal.core.storage.EternalStorage;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GDPR consent gate. Wraps the {@code eternal_consent} table with an
 * in-memory "pending" tracker that the listeners use to freeze players
 * who joined but haven't responded to the privacy prompt yet.
 *
 * <p>The pending tracker is purely transient: it lives only as long
 * as the player is online. Reconnects reset it — we re-check the DB
 * and either send them straight through (consent on file) or prompt
 * again (no consent).</p>
 *
 * <p>Three operations the listeners and command care about:</p>
 * <ul>
 *     <li>{@link #isPending} — "is this player frozen waiting for
 *         accept/decline?" — drives the listener freeze.</li>
 *     <li>{@link #accept} — record the acceptance, lift the freeze,
 *         backfill the profile/session writes that were skipped on
 *         join.</li>
 *     <li>{@link #decline} — kick + wipe every personal-data row
 *         we hold (and the CloudNet user record, when present).</li>
 * </ul>
 */
public final class ConsentService {

    private final EternalSpigot plugin;
    private final EternalStorage storage;
    /** UUIDs that are currently online + waiting for accept/decline.
     *  Populated by the join listener, drained by accept/decline. */
    private final ConcurrentHashMap<UUID, Boolean> pending = new ConcurrentHashMap<>();

    public ConsentService(@NotNull EternalSpigot plugin, @NotNull EternalStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    /** Pure DB lookup — true iff there's an {@code eternal_consent} row
     *  for this UUID. The {@link #pending} flag is the runtime view. */
    public boolean hasConsent(@NotNull UUID uuid) {
        return storage.hasConsent(uuid);
    }

    public boolean isPending(@NotNull UUID uuid) {
        return pending.containsKey(uuid);
    }

    /** Scoreboard-tag we stamp on pending players so other plugins
     *  (notably eternal-replay's continuous recorder) can skip them
     *  without needing a hard reference back to this service.
     *  Scoreboard tags survive across servers in a Bungee network but
     *  not across player disconnects, which is exactly the lifetime
     *  we want here. */
    public static final String PENDING_TAG = "eternal_pending_consent";

    /** Marks the player as awaiting a decision. Called from the join
     *  listener when {@link #hasConsent} returns false. */
    public void markPending(@NotNull UUID uuid) {
        pending.put(uuid, Boolean.TRUE);
        Player p = plugin.getServer().getPlayer(uuid);
        if (p != null) p.addScoreboardTag(PENDING_TAG);
    }

    /** Drops the pending flag without recording anything — used when
     *  the player disconnects before responding. The next reconnect
     *  will re-prompt them; we don't auto-deny. */
    public void clearPending(@NotNull UUID uuid) {
        pending.remove(uuid);
        Player p = plugin.getServer().getPlayer(uuid);
        if (p != null) p.removeScoreboardTag(PENDING_TAG);
    }

    /**
     * Player chose accept. Records the consent row, drops them from
     * the pending set, and backfills the profile+session writes that
     * {@link de.eternal.spigot.listener.ConnectionListener} skipped
     * on join.
     */
    public void accept(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        String ip = player.getAddress() == null ? "?" : player.getAddress().getAddress().getHostAddress();
        storage.recordConsent(uuid, player.getName(), ip);
        pending.remove(uuid);
        player.removeScoreboardTag(PENDING_TAG);
        plugin.getLogger().info("Consent granted: " + player.getName() + " (" + uuid + ")");
        // Trigger the profile + session writes that were gated on
        // consent. We re-run the listener path so the same code
        // populates profile + login_session + display-name capture.
        plugin.connectionListener().runPostConsent(player);
    }

    /**
     * Player declined. Wipe every eternal-side record for them, ask
     * CloudNet to drop its user row too (best-effort, via reflection),
     * then kick. The pending flag is also dropped — even though the
     * kick takes them offline anyway, the disconnect listener might
     * race against us.
     */
    public void decline(@NotNull Player player, @NotNull String kickMessage) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        pending.remove(uuid);
        // Disk + DB cleanup. The plugin.storage().purgePersonalData
        // skips moderation history so a banned player can't decline
        // their way out of a ban — that survives on legitimate-
        // interest grounds. Run async so the kick happens fast.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int rows = storage.purgePersonalData(uuid);
            plugin.getLogger().info("Consent declined by " + name + " (" + uuid + ")"
                    + " — purged " + rows + " personal-data rows from Eternal.");
            tryDeleteCloudNetUser(uuid, name);
        });
        // Kick on the main thread — Bukkit refuses player.kickPlayer
        // from async contexts.
        plugin.getServer().getScheduler().runTask(plugin, () ->
                player.kickPlayer(org.bukkit.ChatColor.translateAlternateColorCodes('&', kickMessage)));
    }

    /** Best-effort CloudNet user delete. Uses reflection because we
     *  soft-depend on CloudNet (different versions / not present at
     *  all). Failure is logged but doesn't propagate. */
    private void tryDeleteCloudNetUser(@NotNull UUID uuid, @NotNull String name) {
        // Try to locate PermissionManagement the same way CloudPermsAccess
        // does — through CN4's InjectionLayer or CN3's CloudNetDriver.
        String[] managementTypes = {
                "eu.cloudnetservice.driver.permission.PermissionManagement",
                "de.dytanic.cloudnet.driver.permission.IPermissionManagement"
        };
        Object mgmt = null;
        Class<?> mgmtClass = null;
        for (String type : managementTypes) {
            try {
                mgmtClass = Class.forName(type);
                mgmt = locateManagement(mgmtClass);
                if (mgmt != null) break;
            } catch (ClassNotFoundException ignored) {}
        }
        if (mgmt == null) {
            plugin.getLogger().fine("CloudNet not present — skipping CN user purge for " + name);
            return;
        }
        // CN3 uses deleteUser(UUID), CN4 uses deletePermissionUser(UUID).
        // Try both, log if neither works.
        for (String mname : new String[]{"deletePermissionUser", "deleteUser"}) {
            try {
                Method m = mgmtClass.getMethod(mname, UUID.class);
                m.invoke(mgmt, uuid);
                plugin.getLogger().info("CloudNet user " + name + " removed via " + mname + "().");
                return;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                plugin.getLogger().warning("CloudNet " + mname + " failed for " + name + ": " + t.getMessage());
                return;
            }
        }
        plugin.getLogger().warning("CloudNet present but no delete method found — manual cleanup may be needed for " + name);
    }

    private static Object locateManagement(@NotNull Class<?> managementType) {
        // Mirrors CloudPermsAccess.locateManagement — kept private here
        // to avoid a hard cross-module dependency just for one helper.
        String[] holders = {
                "eu.cloudnetservice.driver.inject.InjectionLayer",
                "eu.cloudnetservice.driver.CloudNetDriver",
                "de.dytanic.cloudnet.driver.CloudNetDriver"
        };
        for (String holderName : holders) {
            try {
                Class<?> holder = Class.forName(holderName);
                if (holderName.endsWith("InjectionLayer")) {
                    Method boot = holder.getMethod("boot");
                    Object layer = boot.invoke(null);
                    Method instance = layer.getClass().getMethod("instance", Class.class);
                    return instance.invoke(layer, managementType);
                }
                Method getInstance;
                try { getInstance = holder.getMethod("instance"); }
                catch (NoSuchMethodException ex) { getInstance = holder.getMethod("getInstance"); }
                Object driver = getInstance.invoke(null);
                Method getPerms;
                try { getPerms = driver.getClass().getMethod("permissionManagement"); }
                catch (NoSuchMethodException ex) { getPerms = driver.getClass().getMethod("getPermissionManagement"); }
                return getPerms.invoke(driver);
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
