package de.eternal.lobby.gui;

import de.eternal.lobby.EternalLobby;
import de.eternal.lobby.LobbyItems;
import de.eternal.lobby.cosmetic.Trails;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Cosmetics menu — pick a particle trail ("boots"). Slot 0 = off; trails fill
 *  from slot 10 onward. The active choice is marked. */
public final class CosmeticsGui {

    private static final int OFF_SLOT = 0;
    private static final int FIRST_TRAIL_SLOT = 10;

    private final EternalLobby plugin;

    public CosmeticsGui(@NotNull EternalLobby plugin) {
        this.plugin = plugin;
    }

    public @NotNull String title() {
        return LobbyItems.color(plugin.getConfig().getString("cosmetics.gui-title", "&8Cosmetics"));
    }

    public void open(@NotNull Player p) {
        int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("cosmetics.gui-rows", 3)));
        Inventory inv = Bukkit.createInventory(null, rows * 9, title());
        String current = plugin.trails().choice(p);

        inv.setItem(OFF_SLOT, item(Material.BARRIER, "&cTrail aus",
                Trails.NONE.equals(current) ? "&aAktiv" : "&7Klicke zum Deaktivieren"));

        List<Trails.Trail> trails = plugin.trails().all();
        for (int i = 0; i < trails.size(); i++) {
            Trails.Trail t = trails.get(i);
            int slot = FIRST_TRAIL_SLOT + i;
            if (slot >= rows * 9) break;
            boolean active = t.id().equalsIgnoreCase(current);
            inv.setItem(slot, item(t.icon(), t.name(),
                    active ? "&aAktiv" : "&7Klicke zum Auswaehlen"));
        }
        p.openInventory(inv);
    }

    /** Trail id for a clicked slot ({@code Trails.NONE}, a trail id, or null). */
    public @Nullable String idForSlot(int slot) {
        if (slot == OFF_SLOT) return Trails.NONE;
        int idx = slot - FIRST_TRAIL_SLOT;
        List<Trails.Trail> trails = plugin.trails().all();
        if (idx >= 0 && idx < trails.size()) return trails.get(idx).id();
        return null;
    }

    private static @NotNull ItemStack item(@NotNull Material mat, @NotNull String name, @NotNull String lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(LobbyItems.color(name));
            meta.setLore(List.of(LobbyItems.color(lore)));
            it.setItemMeta(meta);
        }
        return it;
    }
}
