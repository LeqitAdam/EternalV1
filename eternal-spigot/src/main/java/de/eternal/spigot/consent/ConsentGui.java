package de.eternal.spigot.consent;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Chest-GUI for the GDPR consent prompt. Single-row inventory with two
 * clickable choices (lime concrete = accept, red concrete = decline) and
 * an info item in the middle holding the full privacy text as lore.
 *
 * <p>Replaces the older chat-based prompt that relied on {@code /eternal
 * accept} — clickable chat commands break on a BungeeCord network where
 * the {@code /eternal} command is intercepted at the proxy. Inventory
 * clicks never leave the backing Spigot, so this path works regardless
 * of the network topology.</p>
 *
 * <p>The holder class is used by the click + close listeners to
 * recognise the GUI without title-string matching (which breaks on
 * locale switch).</p>
 */
public final class ConsentGui {

    /** Marker holder so the listener can {@code instanceof} our inventory. */
    public static final class Holder implements InventoryHolder {
        private Inventory inv;
        @Override public @NotNull Inventory getInventory() { return inv; }
        void setInventory(@NotNull Inventory inv) { this.inv = inv; }
    }

    /** PDC keys to tag the two choice items so the listener doesn't
     *  have to compare display names (which translate). */
    public enum Choice { ACCEPT, DECLINE }

    private final EternalSpigot plugin;
    private final NamespacedKey choiceKey;

    public ConsentGui(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
        this.choiceKey = new NamespacedKey(plugin, "consent_choice");
    }

    public @NotNull NamespacedKey choiceKey() {
        return choiceKey;
    }

    /** Opens the consent GUI for {@code player}. Idempotent — opening
     *  while it's already open just refocuses the existing window. */
    public void open(@NotNull Player player) {
        Holder holder = new Holder();
        // 9 slots = one row. Wider grids look empty for just two
        // actions; this matches the size of a typical confirm dialog.
        String title = ChatColor.translateAlternateColorCodes('&',
                plugin.messages().format("consent-gui-title"));
        Inventory inv = Bukkit.createInventory(holder, 9, title);
        holder.setInventory(inv);

        // Slot layout:
        //  [ . . accept . info . decline . . ]
        //    0 1   2    3   4    5      6 7 8
        // Accept on the left because German UI conventions read L→R
        // and the affirmative action belongs first.
        inv.setItem(2, acceptItem());
        inv.setItem(4, infoItem(player.getName()));
        inv.setItem(6, declineItem());

        player.openInventory(inv);
    }

    private @NotNull ItemStack acceptItem() {
        ItemStack item = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                plugin.messages().format("consent-gui-accept-name")));
        meta.setLore(legacyLines(plugin.messages().format("consent-gui-accept-lore")));
        meta.getPersistentDataContainer().set(choiceKey, PersistentDataType.STRING, Choice.ACCEPT.name());
        item.setItemMeta(meta);
        return item;
    }

    private @NotNull ItemStack declineItem() {
        ItemStack item = new ItemStack(Material.RED_CONCRETE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                plugin.messages().format("consent-gui-decline-name")));
        meta.setLore(legacyLines(plugin.messages().format("consent-gui-decline-lore")));
        meta.getPersistentDataContainer().set(choiceKey, PersistentDataType.STRING, Choice.DECLINE.name());
        item.setItemMeta(meta);
        return item;
    }

    /** The info item in the middle carries the actual privacy text as
     *  lore so the player reads it before clicking. No PDC marker —
     *  clicking the info item does nothing (no choice key). */
    private @NotNull ItemStack infoItem(@NotNull String playerName) {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                plugin.messages().format("consent-gui-info-name", "player", playerName)));
        meta.setLore(legacyLines(plugin.messages().format("consent-gui-info-lore",
                "player", playerName)));
        item.setItemMeta(meta);
        return item;
    }

    /** Splits a translation that uses literal "\n" between lines into
     *  a List<String> with all {@code &}-codes resolved. */
    private static @NotNull java.util.List<String> legacyLines(@NotNull String raw) {
        return Arrays.stream(raw.split("\n"))
                .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                .toList();
    }

    /** Returns the choice tag on {@code item}, or null if it's not one
     *  of the two action items (e.g. the info book or a stray click on
     *  empty slot). Used by the listener. */
    public @org.jetbrains.annotations.Nullable Choice choiceOf(@NotNull ItemStack item) {
        if (item.getItemMeta() == null) return null;
        String tag = item.getItemMeta().getPersistentDataContainer().get(choiceKey, PersistentDataType.STRING);
        if (tag == null) return null;
        try { return Choice.valueOf(tag); }
        catch (IllegalArgumentException ex) { return null; }
    }
}
