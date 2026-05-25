package de.eternal.bungee.listener;

import de.eternal.bungee.EternalBungee;
import de.eternal.core.integration.CloudPermsAccess;
import de.eternal.core.integration.GroupMapping;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates CloudNet group membership into Eternal permission nodes
 * ({@code eternal.tier.X}, {@code eternal.ban} etc.) when CloudNet-CloudPerms
 * is present.
 *
 * <p>This used to live in a separate {@code eternal-cloudnet} JAR. It now
 * ships inside the regular Bungee plugin and only attaches when
 * {@link CloudPermsAccess#available()} returns true — so on a non-CloudNet
 * proxy the listener is a no-op and the operator just configures
 * {@code eternal.*} permissions manually.</p>
 */
public final class CloudNetBridgeListener implements Listener {

    private final EternalBungee plugin;
    private final CloudPermsAccess cloudPerms;
    private final GroupMapping mapping;
    /** Tracks which dynamic permissions we set per player, so we can revoke
     *  exactly those on disconnect and not nuke anything granted elsewhere. */
    private final ConcurrentHashMap<UUID, Set<String>> applied = new ConcurrentHashMap<>();

    public CloudNetBridgeListener(@NotNull EternalBungee plugin,
                                  @NotNull CloudPermsAccess cloudPerms,
                                  @NotNull GroupMapping mapping) {
        this.plugin = plugin;
        this.cloudPerms = cloudPerms;
        this.mapping = mapping;
    }

    /** Applies the mapping to players already online when the listener is
     *  registered — useful after a /eternal reload. */
    public void applyToOnline() {
        for (ProxiedPlayer p : plugin.getProxy().getPlayers()) applyTo(p);
    }

    @EventHandler
    public void onPostLogin(@NotNull PostLoginEvent event) {
        applyTo(event.getPlayer());
    }

    @EventHandler
    public void onDisconnect(@NotNull PlayerDisconnectEvent event) {
        Set<String> prev = applied.remove(event.getPlayer().getUniqueId());
        if (prev == null) return;
        for (String perm : prev) event.getPlayer().setPermission(perm, false);
    }

    private void applyTo(@NotNull ProxiedPlayer player) {
        var groups = cloudPerms.groupsOf(player.getUniqueId());
        var resolution = mapping.resolve(groups);

        // Revoke previous assignment in case the player's group set changed.
        Set<String> prev = applied.getOrDefault(player.getUniqueId(), Collections.emptySet());
        for (String perm : prev) player.setPermission(perm, false);

        Set<String> next = new HashSet<>(resolution.permissions());
        for (String perm : resolution.permissions()) player.setPermission(perm, true);
        String tierPerm = "eternal.tier." + Math.max(0, Math.min(100, resolution.tier()));
        player.setPermission(tierPerm, true);
        next.add(tierPerm);
        applied.put(player.getUniqueId(), next);
    }
}
