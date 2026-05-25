package de.eternal.replay.internal.playback;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link GhostAvatar}-Adapter um den existierenden ArmorStand-basierten
 * {@link GhostEntity}. Wird benutzt wenn ProtocolLib nicht verfügbar ist.
 */
public final class ArmorStandAvatar implements GhostAvatar {

    private final GhostEntity inner;

    public ArmorStandAvatar(@NotNull GhostEntity inner) { this.inner = inner; }

    @Override public void teleport(@NotNull Location loc) { inner.teleport(loc); }
    @Override public void setMainHand(@Nullable ItemStack item) { inner.setMainHand(item); }
    @Override public void setHelmet(@Nullable ItemStack item)   { if (inner.stand().getEquipment() != null) inner.stand().getEquipment().setHelmet(item); }
    @Override public void setChest(@Nullable ItemStack item)    { if (inner.stand().getEquipment() != null) inner.stand().getEquipment().setChestplate(item); }
    @Override public void setLeggings(@Nullable ItemStack item) { if (inner.stand().getEquipment() != null) inner.stand().getEquipment().setLeggings(item); }
    @Override public void setBoots(@Nullable ItemStack item)    { if (inner.stand().getEquipment() != null) inner.stand().getEquipment().setBoots(item); }
    @Override public void remove() { inner.remove(); }
}
