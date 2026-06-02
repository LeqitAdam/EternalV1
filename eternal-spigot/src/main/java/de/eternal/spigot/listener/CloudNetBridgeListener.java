package de.eternal.spigot.listener;

import de.eternal.core.integration.CloudPermsAccess;
import de.eternal.core.integration.GroupMapping;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Spigot counterpart to the Bungee CloudNet bridge. Reads the same
 * {@code cloudnet.groups} mapping from config.yml and attaches Eternal perms
 * via {@link PermissionAttachment} so commands like /report and listeners
 * that check {@code eternal.*} see the right values.
 *
 * <p>Only registered when {@link CloudPermsAccess#available()} is true.</p>
 */
public final class CloudNetBridgeListener implements Listener {

    private final EternalSpigot plugin;
    private final CloudPermsAccess cloudPerms;
    private final GroupMapping mapping;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public CloudNetBridgeListener(@NotNull EternalSpigot plugin,
                                  @NotNull CloudPermsAccess cloudPerms,
                                  @NotNull GroupMapping mapping) {
        this.plugin = plugin;
        this.cloudPerms = cloudPerms;
        this.mapping = mapping;
    }

    /** Apply to currently-online players too, e.g. after a /eternal reload. */
    public void applyToOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) applyTo(p);
    }

    /** Public re-apply for one player — called by the ActionPoller's
     *  PERM_REFRESH handler after a web-driven permission/rank change so
     *  the player sees the new {@code eternal.*} perms without rejoining. */
    public void reapply(@NotNull Player player) {
        applyTo(player);
    }

    public void detachAll() {
        for (PermissionAttachment a : attachments.values()) {
            try { a.remove(); } catch (Exception ignored) {}
        }
        attachments.clear();
    }

    // Higher priority than the cache-DisplayName listener so the perms are in
    // place before any other listener reads hasPermission.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        applyTo(event.getPlayer());
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        PermissionAttachment att = attachments.remove(event.getPlayer().getUniqueId());
        if (att != null) {
            try { att.remove(); } catch (Exception ignored) {}
        }
    }

    private void applyTo(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        var groups = cloudPerms.groupsOf(uuid);
        var resolution = mapping.resolve(groups);

        // Drop any previous attachment so changing groups while online cleanly
        // re-applies rather than stacking up.
        PermissionAttachment prev = attachments.remove(uuid);
        if (prev != null) {
            try { prev.remove(); } catch (Exception ignored) {}
        }

        PermissionAttachment att = player.addAttachment(plugin);

        // Layer 1 — config.yml group mapping (legacy default). Lowest
        // precedence; the DB grants below override these.
        for (String perm : resolution.permissions()) att.setPermission(perm, true);
        String tierPerm = "eternal.tier." + Math.max(0, Math.min(100, resolution.tier()));
        att.setPermission(tierPerm, true);

        // Layer 2 + 3 — the web-managed DB grants. This is what makes a
        // permission edited in the dashboard actually take effect
        // in-game. Role grants (for the role bound to the player's
        // CloudNet group) then user overrides on top. setPermission with
        // an explicit false produces a real deny that beats the config
        // mapping's allow, so "deny X for this one mod" works.
        applyDbGrants(uuid, groups, att);

        attachments.put(uuid, att);
    }

    /** Reads eternal_role_permissions (via the group→role mapping) and
     *  eternal_user_permissions and writes them onto the attachment.
     *  Fail-soft: any storage hiccup leaves the config-mapping perms
     *  intact rather than throwing during a join. */
    private void applyDbGrants(@NotNull UUID uuid, @NotNull java.util.List<String> groups,
                               @NotNull PermissionAttachment att) {
        if (!(plugin.storage() instanceof de.eternal.core.permission.PermissionStorage perms)) return;
        try {
            // Role layer: find the role whose mcGroupName matches any of
            // the player's CloudNet groups, apply its grants.
            for (String group : groups) {
                var role = perms.findRoleByMcGroup(group);
                if (role.isEmpty()) continue;
                for (var grant : perms.rolePermissions(role.get().name()).values()) {
                    att.setPermission(grant.permissionKey(), grant.granted());
                }
            }
            // User layer: per-player overrides win over role + config.
            for (var grant : perms.userPermissions(uuid).values()) {
                att.setPermission(grant.permissionKey(), grant.granted());
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("applyDbGrants failed for " + uuid + ": " + ex.getMessage());
        }
    }
}
