package de.eternal.replay.internal.playback;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One armor-stand acting as a stand-in for a recorded player during
 * playback. Vanilla Bukkit doesn't expose fake-player NPCs, so we render
 * a name-tagged armor stand carrying a player-head skull in the helmet
 * slot — close enough for the mod to see "this is LeqitAdam".
 *
 * <p>The stand is marked {@code small=false}, {@code basePlate=false},
 * {@code arms=true} so it more closely resembles a player silhouette.
 * It's also tagged invulnerable + no-gravity so it doesn't get
 * accidentally killed or knocked around.</p>
 */
public final class GhostEntity {

    private final ArmorStand stand;
    private final UUID representedUuid;
    private final String representedName;

    public GhostEntity(@NotNull ArmorStand stand, @NotNull UUID representedUuid, @NotNull String representedName) {
        this.stand = stand;
        this.representedUuid = representedUuid;
        this.representedName = representedName;
    }

    public static @NotNull GhostEntity spawn(@NotNull Location loc, @NotNull UUID uuid, @NotNull String name) {
        ArmorStand stand = loc.getWorld().spawn(loc, ArmorStand.class, s -> {
            s.setInvulnerable(true);
            s.setGravity(false);
            s.setSmall(false);
            s.setBasePlate(false);
            s.setArms(true);
            s.setCustomName(name);
            s.setCustomNameVisible(true);
            s.setCanPickupItems(false);
            // Skull head matches the recorded player so the mod can tell
            // who's who at a glance.
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta sm) {
                sm.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(uuid));
                head.setItemMeta(sm);
            }
            s.getEquipment().setHelmet(head);
        });
        return new GhostEntity(stand, uuid, name);
    }

    public @NotNull ArmorStand stand() { return stand; }
    public @NotNull UUID representedUuid() { return representedUuid; }
    public @NotNull String representedName() { return representedName; }

    public void teleport(@NotNull Location to) {
        // teleport() respects yaw/pitch so head direction follows the
        // recorded rotation. Smooth enough at 5 Hz frame rate.
        stand.teleport(to);
    }

    public void setMainHand(@Nullable ItemStack item) {
        if (stand.getEquipment() != null) stand.getEquipment().setItemInMainHand(item);
    }

    public void remove() {
        if (!stand.isDead()) stand.remove();
    }
}
