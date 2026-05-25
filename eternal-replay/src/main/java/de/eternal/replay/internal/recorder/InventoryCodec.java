package de.eternal.replay.internal.recorder;

import de.eternal.replay.model.InventorySnapshot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

import java.util.Base64;

/**
 * Bukkit's official way to (de)serialize {@link ItemStack}s is to wrap a
 * YamlConfiguration around them — that gives us a portable, version-safe
 * string we can store as base64 inside the replay file.
 *
 * <p>The format is deliberately not pretty — it's a YAML dump of an
 * ItemStack[] indexed 0..40 (main 0-35, armor 36-39, offhand 40). The
 * playback side reverses the operation to restore real items in the
 * ghost entity's hand or armour slots.</p>
 */
public final class InventoryCodec {

    private InventoryCodec() {
    }

    public static void snapshot(@NotNull PlayerBuffer buf, @NotNull Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = new ItemStack[41];
        ItemStack[] main = inv.getContents(); // 36 slots
        for (int i = 0; i < main.length && i < 36; i++) contents[i] = main[i];
        ItemStack[] armor = inv.getArmorContents(); // 4 slots
        for (int i = 0; i < armor.length && i < 4; i++) contents[36 + i] = armor[i];
        contents[40] = inv.getItemInOffHand();

        YamlConfiguration y = new YamlConfiguration();
        y.set("inv", contents);
        String yaml = y.saveToString();
        String b64 = Base64.getEncoder().encodeToString(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        buf.push(new InventorySnapshot(buf.currentRelativeMs(), 0, b64));
    }

    /** Decode a snapshot back into a 41-slot array. May contain nulls. */
    public static @NotNull ItemStack[] decode(@NotNull String b64) {
        byte[] bytes = Base64.getDecoder().decode(b64);
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (org.bukkit.configuration.InvalidConfigurationException ex) {
            return new ItemStack[41];
        }
        @SuppressWarnings("unchecked")
        java.util.List<ItemStack> list = (java.util.List<ItemStack>) y.getList("inv");
        ItemStack[] out = new ItemStack[41];
        if (list != null) {
            for (int i = 0; i < list.size() && i < 41; i++) out[i] = list.get(i);
        }
        return out;
    }
}
