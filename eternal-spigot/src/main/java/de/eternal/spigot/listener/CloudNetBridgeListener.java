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
        var groups = cloudPerms.groupsOf(player.getUniqueId());
        var resolution = mapping.resolve(groups);

        // Drop any previous attachment so changing groups while online cleanly
        // re-applies rather than stacking up.
        PermissionAttachment prev = attachments.remove(player.getUniqueId());
        if (prev != null) {
            try { prev.remove(); } catch (Exception ignored) {}
        }

        PermissionAttachment att = player.addAttachment(plugin);
        for (String perm : resolution.permissions()) att.setPermission(perm, true);
        String tierPerm = "eternal.tier." + Math.max(0, Math.min(100, resolution.tier()));
        att.setPermission(tierPerm, true);
        attachments.put(player.getUniqueId(), att);
    }
}
