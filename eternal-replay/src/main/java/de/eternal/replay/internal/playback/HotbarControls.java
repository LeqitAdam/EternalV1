package de.eternal.replay.internal.playback;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

/**
 * Manages the playback hotbar layout. Each control sits in a fixed slot:
 *
 * <pre>
 *  0 = Smaragd       Play/Pause
 *  1 = Pfeil          Seek -10s
 *  3 = Lehmball       Speed -0.25
 *  4 = Karte          Replay info (chat)
 *  5 = Schleimball    Speed +0.25
 *  7 = Spektral-Pfeil Seek +10s
 *  8 = Barriere       Exit
 * </pre>
 *
 * <p>Items are tagged with PersistentData so the click listener can tell
 * a replay-control from whatever the viewer was carrying before.</p>
 */
public final class HotbarControls {

    public static final NamespacedKey CONTROL_KEY = NamespacedKey.fromString("eternal_replay:control");

    /** Tag value tells the listener what action to run. */
    public enum Control {
        PLAY_PAUSE, SEEK_BACK, SPEED_DOWN, INFO, SPEED_UP, SEEK_FWD, EXIT, OPEN_INVENTORY
    }

    private HotbarControls() {
    }

    public static void install(@NotNull Player viewer) {
        viewer.getInventory().clear();
        viewer.getInventory().setItem(0, item(Material.EMERALD, "§a§lPlay / Pause", Control.PLAY_PAUSE));
        viewer.getInventory().setItem(1, item(Material.ARROW, "§b§l« -10s", Control.SEEK_BACK));
        viewer.getInventory().setItem(2, item(Material.CHEST, "§e§lInventar (nähester Spieler)", Control.OPEN_INVENTORY));
        viewer.getInventory().setItem(3, item(Material.CLAY_BALL, "§7§lSpeed -", Control.SPEED_DOWN));
        viewer.getInventory().setItem(4, item(Material.MAP, "§d§lReplay-Info", Control.INFO));
        viewer.getInventory().setItem(5, item(Material.SLIME_BALL, "§a§lSpeed +", Control.SPEED_UP));
        viewer.getInventory().setItem(7, item(Material.SPECTRAL_ARROW, "§b§l+10s »", Control.SEEK_FWD));
        viewer.getInventory().setItem(8, item(Material.BARRIER, "§c§lReplay verlassen", Control.EXIT));
        viewer.getInventory().setHeldItemSlot(0);
    }

    private static @NotNull ItemStack item(@NotNull Material m, @NotNull String name, @NotNull Control ctrl) {
        ItemStack i = new ItemStack(m);
        ItemMeta meta = i.getItemMeta();
        if (meta == null) return i;
        meta.setDisplayName(name);
        if (CONTROL_KEY != null) {
            meta.getPersistentDataContainer().set(CONTROL_KEY, PersistentDataType.STRING, ctrl.name());
        }
        i.setItemMeta(meta);
        return i;
    }

    /** Returns the control bound to {@code item}, or null when it isn't
     *  one of our tagged controls. */
    public static Control read(@NotNull ItemStack item) {
        if (item.getItemMeta() == null || CONTROL_KEY == null) return null;
        String tag = item.getItemMeta().getPersistentDataContainer()
                .get(CONTROL_KEY, PersistentDataType.STRING);
        if (tag == null) return null;
        try { return Control.valueOf(tag); }
        catch (IllegalArgumentException ex) { return null; }
    }
}
