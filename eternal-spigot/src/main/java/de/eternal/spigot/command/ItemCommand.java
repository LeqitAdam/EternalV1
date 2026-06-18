package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /hat, /repair, /more, /sign. */
public final class ItemCommand implements CommandExecutor {

    private final EternalSpigot plugin;

    public ItemCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Player p = Cmd.player(plugin, sender);
        if (p == null) return true;
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "hat" -> hat(p);
            case "repair" -> repair(p, args);
            case "more" -> more(p);
            case "sign" -> sign(p);
            default -> { }
        }
        return true;
    }

    private void hat(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        if (isEmpty(hand)) {
            plugin.messages().send(p, "empty-hand");
            return;
        }
        ItemStack oldHelmet = inv.getHelmet();
        inv.setHelmet(hand.clone());
        inv.setItemInMainHand(oldHelmet == null ? new ItemStack(Material.AIR) : oldHelmet);
        plugin.messages().send(p, "hat-done");
    }

    private void repair(Player p, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("all")) {
            int count = 0;
            for (ItemStack item : p.getInventory().getContents()) count += repairOne(item) ? 1 : 0;
            for (ItemStack item : p.getInventory().getArmorContents()) count += repairOne(item) ? 1 : 0;
            plugin.messages().send(p, "repair-all", "count", count);
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isEmpty(hand)) {
            plugin.messages().send(p, "empty-hand");
            return;
        }
        if (repairOne(hand)) plugin.messages().send(p, "repair-done");
        else plugin.messages().send(p, "repair-unrepairable");
    }

    /** Resets durability damage on a damageable item; true if it changed. */
    private boolean repairOne(ItemStack item) {
        if (isEmpty(item)) return false;
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable dmg)) return false;
        if (dmg.getDamage() == 0) return false;
        dmg.setDamage(0);
        item.setItemMeta(meta);
        return true;
    }

    private void more(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isEmpty(hand)) {
            plugin.messages().send(p, "empty-hand");
            return;
        }
        hand.setAmount(hand.getMaxStackSize());
        plugin.messages().send(p, "more-done");
    }

    private void sign(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isEmpty(hand)) {
            plugin.messages().send(p, "empty-hand");
            return;
        }
        ItemMeta meta = hand.getItemMeta();
        if (meta == null) {
            plugin.messages().send(p, "empty-hand");
            return;
        }
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        // getDisplayName() carries the rank-coloured name from the chat plugin.
        String signer = p.getDisplayName();
        String date = plugin.nowFormatted();
        lore.add(plugin.messages().format("sign-line-1", "signer", signer));
        lore.add(plugin.messages().format("sign-line-2", "date", date));
        meta.setLore(lore);
        hand.setItemMeta(meta);
        plugin.messages().send(p, "item-signed");
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }
}
