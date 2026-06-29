package de.eternal.lobby.gui;

import de.eternal.lobby.EternalLobby;
import de.eternal.lobby.LobbyItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Server-selector GUI opened by the navigator compass. Entries come from
 *  {@code navigator.servers} in config.yml; a click connects via BungeeCord. */
public final class NavigatorGui {

    private final EternalLobby plugin;

    public NavigatorGui(@NotNull EternalLobby plugin) {
        this.plugin = plugin;
    }

    public @NotNull String title() {
        return LobbyItems.color(plugin.getConfig().getString("navigator.gui-title", "&8Server"));
    }

    public void open(@NotNull Player p) {
        int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("navigator.gui-rows", 3)));
        Inventory inv = Bukkit.createInventory(null, rows * 9, title());
        for (Map<?, ?> s : plugin.getConfig().getMapList("navigator.servers")) {
            int slot = asInt(s.get("slot"), -1);
            if (slot < 0 || slot >= rows * 9) continue;
            Material mat = LobbyItems.material(str(s.get("material")), Material.PAPER);
            inv.setItem(slot, item(mat, str(s.get("name")), str(s.get("lore"))));
        }
        p.openInventory(inv);
    }

    /** The BungeeCord server name bound to the clicked slot, or null. */
    public @Nullable String serverForSlot(int slot) {
        for (Map<?, ?> s : plugin.getConfig().getMapList("navigator.servers")) {
            if (asInt(s.get("slot"), -1) == slot) return str(s.get("server"));
        }
        return null;
    }

    private static @NotNull ItemStack item(@NotNull Material mat, @Nullable String name, @Nullable String lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            if (name != null) meta.setDisplayName(LobbyItems.color(name));
            if (lore != null && !lore.isEmpty()) {
                List<String> l = new ArrayList<>();
                for (String line : lore.split("\\|")) l.add(LobbyItems.color(line));
                meta.setLore(l);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static int asInt(@Nullable Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static @Nullable String str(@Nullable Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
