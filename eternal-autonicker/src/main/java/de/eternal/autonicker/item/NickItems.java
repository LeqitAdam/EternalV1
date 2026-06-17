package de.eternal.autonicker.item;

import de.eternal.autonicker.EternalAutonicker;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Builds + recognises the hotbar nametag toggle item (PDC-tagged). */
public final class NickItems {

    private NickItems() {
    }

    public static @NotNull ItemStack tag(@NotNull EternalAutonicker plugin, boolean nicked) {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String state = plugin.messages().format(nicked ? "state-on" : "state-off");
            meta.setDisplayName(plugin.messages().format("item-nametag-name"));
            meta.setLore(plugin.messages().lines("item-nametag-lore", "state", state));
            meta.getPersistentDataContainer().set(plugin.tagKey(), PersistentDataType.STRING, "1");
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isTag(@NotNull EternalAutonicker plugin, @Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return "1".equals(meta.getPersistentDataContainer().get(plugin.tagKey(), PersistentDataType.STRING));
    }
}
