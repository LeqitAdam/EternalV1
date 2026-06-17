package de.eternal.party.spigot.gui;

import de.eternal.party.spigot.EternalPartySpigot;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Small builders for the action / display / player-head items used by GUIs. */
final class Buttons {

    private Buttons() {
    }

    static @NotNull ItemStack action(@NotNull EternalPartySpigot plugin, @NotNull Material mat,
                                     @NotNull String nameKey, @Nullable String loreKey, @NotNull String action,
                                     @NotNull Object... namePairs) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.messages().format(nameKey, namePairs));
            if (loreKey != null) meta.setLore(plugin.messages().lines(loreKey));
            meta.getPersistentDataContainer().set(plugin.actionKey(), PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Display item with a literal (already-rendered) name + lore lines and an
     *  optional action tag. */
    static @NotNull ItemStack display(@NotNull Material mat, @NotNull String name, @NotNull List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** A read-only player head for {@code owner} (skin set, no PDC tags). */
    static @NotNull ItemStack displayHead(@NotNull OfflinePlayer owner, @NotNull String name, @NotNull List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) skull.setOwningPlayer(owner);
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** A player head for {@code owner}, tagged with an action + target UUID. */
    static @NotNull ItemStack playerHead(@NotNull EternalPartySpigot plugin, @NotNull OfflinePlayer owner,
                                         @NotNull String name, @NotNull List<String> lore,
                                         @NotNull String action, @NotNull UUID target) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) skull.setOwningPlayer(owner);
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(plugin.actionKey(), PersistentDataType.STRING, action);
            meta.getPersistentDataContainer().set(plugin.targetKey(), PersistentDataType.STRING, target.toString());
            item.setItemMeta(meta);
        }
        return item;
    }
}
