package de.eternal.party.spigot.item;

import de.eternal.party.spigot.EternalPartySpigot;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds + recognises the hotbar party-head item. Tagged with a PDC string so
 * identification survives renames / locale changes — never matched by display
 * name.
 */
public final class PartyItems {

    private PartyItems() {
    }

    public static @NotNull ItemStack head(@NotNull EternalPartySpigot plugin, @NotNull OfflinePlayer owner) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(owner);
        }
        if (meta != null) {
            meta.setDisplayName(plugin.messages().format("item-party-head-name"));
            meta.setLore(plugin.messages().lines("item-party-head-lore"));
            meta.getPersistentDataContainer().set(plugin.headKey(), PersistentDataType.STRING, "1");
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isHead(@NotNull EternalPartySpigot plugin, @Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return "1".equals(meta.getPersistentDataContainer().get(plugin.headKey(), PersistentDataType.STRING));
    }
}
