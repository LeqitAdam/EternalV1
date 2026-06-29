package de.eternal.spigot.nick;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/** Builds + recognises the hotbar nametag toggle item (PDC-tagged). */
public final class NickItems {

    private NickItems() {
    }

    public static @NotNull ItemStack tag(@NotNull EternalSpigot plugin, boolean nicked) {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String state = plugin.messages().format(nicked ? "state-on" : "state-off");
            meta.setDisplayName(plugin.messages().format("item-nametag-name"));
            // Spigot's Messages has no lines(); a block-scalar translation value
            // renders to one string with embedded newlines — split it for lore.
            List<String> lore = Arrays.asList(
                    plugin.messages().format("item-nametag-lore", "state", state).split("\\R"));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(plugin.nickTagKey(), PersistentDataType.STRING, "1");
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isTag(@NotNull EternalSpigot plugin, @Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return "1".equals(meta.getPersistentDataContainer().get(plugin.nickTagKey(), PersistentDataType.STRING));
    }
}
