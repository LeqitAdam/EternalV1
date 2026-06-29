package de.eternal.lobby;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Builds + recognises the lobby hotbar items (navigator, cosmetics) via PDC. */
public final class LobbyItems {

    private LobbyItems() {
    }

    public static @NotNull ItemStack navigator(@NotNull EternalLobby plugin) {
        return tagged(plugin.navKey(),
                material(plugin.getConfig().getString("navigator.material"), Material.COMPASS),
                plugin.getConfig().getString("navigator.name", "&bNavigator"));
    }

    public static @NotNull ItemStack cosmetics(@NotNull EternalLobby plugin) {
        return tagged(plugin.cosmeticsItemKey(),
                material(plugin.getConfig().getString("cosmetics.material"), Material.LEATHER_BOOTS),
                plugin.getConfig().getString("cosmetics.name", "&dCosmetics"));
    }

    public static boolean isNavigator(@NotNull EternalLobby plugin, @Nullable ItemStack item) {
        return hasTag(plugin.navKey(), item);
    }

    public static boolean isCosmetics(@NotNull EternalLobby plugin, @Nullable ItemStack item) {
        return hasTag(plugin.cosmeticsItemKey(), item);
    }

    public static @NotNull String color(@NotNull String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public static @NotNull Material material(@Nullable String name, @NotNull Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name.toUpperCase());
        return m == null ? fallback : m;
    }

    /* ----------------------------------------------------------------- */

    private static @NotNull ItemStack tagged(@NotNull NamespacedKey key, @NotNull Material mat, @Nullable String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) meta.setDisplayName(color(name));
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "1");
            item.setItemMeta(meta);
        }
        return item;
    }

    private static boolean hasTag(@NotNull NamespacedKey key, @Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && "1".equals(meta.getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }
}
