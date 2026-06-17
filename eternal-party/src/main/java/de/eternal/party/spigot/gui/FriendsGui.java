package de.eternal.party.spigot.gui;

import de.eternal.core.model.Friend;
import de.eternal.core.text.MessageBank;
import de.eternal.party.spigot.EternalPartySpigot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Friend list. Each head: left-click = party invite, right-click = remove. */
public final class FriendsGui {

    private static final int CAPACITY = 26; // last slot reserved for "back"

    private FriendsGui() {
    }

    public static void open(@NotNull EternalPartySpigot plugin, @NotNull Player p, @NotNull List<Friend> friends) {
        PartyHolder holder = new PartyHolder(PartyHolder.Type.FRIENDS);
        Inventory inv = Bukkit.createInventory(holder, 27, plugin.messages().format("gui-friends-title"));
        holder.setInventory(inv);

        if (friends.isEmpty()) {
            inv.setItem(13, Buttons.display(Material.BARRIER,
                    plugin.messages().format("gui-friends-empty-name"),
                    plugin.messages().lines("gui-friends-empty-lore")));
        } else {
            int slot = 0;
            for (Friend f : friends) {
                if (slot >= CAPACITY) break;
                boolean online = Bukkit.getPlayer(f.uuid()) != null;
                String status = online ? plugin.messages().format("gui-friend-online")
                        : plugin.messages().format("gui-friend-offline");
                inv.setItem(slot++, Buttons.playerHead(plugin, Bukkit.getOfflinePlayer(f.uuid()),
                        MessageBank.colorize("&f" + f.name()),
                        plugin.messages().lines("gui-friend-entry-lore", "status", status),
                        Actions.FRIEND_ENTRY, f.uuid()));
            }
        }
        inv.setItem(26, Buttons.action(plugin, Material.ARROW, "gui-back-name", null, Actions.BACK));
        p.openInventory(inv);
    }
}
