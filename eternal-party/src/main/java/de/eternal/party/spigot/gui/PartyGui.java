package de.eternal.party.spigot.gui;

import de.eternal.party.spigot.EternalPartySpigot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

/** Party main menu. Layout depends on whether the player is already in a party. */
public final class PartyGui {

    private PartyGui() {
    }

    public static void open(@NotNull EternalPartySpigot plugin, @NotNull Player p,
                            boolean inParty, boolean leader, @NotNull String leaderName, int count) {
        PartyHolder holder = new PartyHolder(PartyHolder.Type.MAIN);
        Inventory inv = Bukkit.createInventory(holder, 27, plugin.messages().format("gui-party-title"));
        holder.setInventory(inv);

        if (!inParty) {
            inv.setItem(11, Buttons.action(plugin, Material.EMERALD,
                    "gui-party-create-name", "gui-party-create-lore", Actions.CREATE));
            inv.setItem(13, Buttons.action(plugin, Material.PLAYER_HEAD,
                    "gui-party-friends-name", "gui-party-friends-lore", Actions.FRIENDS));
            inv.setItem(15, Buttons.action(plugin, Material.COMPARATOR,
                    "gui-party-settings-name", "gui-party-settings-lore", Actions.SETTINGS));
        } else {
            inv.setItem(10, Buttons.display(Material.NETHER_STAR,
                    plugin.messages().format("gui-party-info-name"),
                    plugin.messages().lines("gui-party-info-lore",
                            "leader", leaderName, "count", count, "max", plugin.partyConfig().maxMembers())));
            inv.setItem(12, Buttons.action(plugin, Material.PLAYER_HEAD,
                    "gui-party-friends-name", "gui-party-friends-lore", Actions.FRIENDS));
            inv.setItem(13, Buttons.action(plugin, Material.CHEST,
                    "gui-party-members-name", "gui-party-members-lore", Actions.MEMBERS));
            inv.setItem(14, Buttons.action(plugin, Material.COMPARATOR,
                    "gui-party-settings-name", "gui-party-settings-lore", Actions.SETTINGS));
            inv.setItem(15, Buttons.action(plugin, Material.BARRIER,
                    "gui-party-leave-name", "gui-party-leave-lore", Actions.LEAVE));
            if (leader) {
                inv.setItem(16, Buttons.action(plugin, Material.TNT,
                        "gui-party-disband-name", "gui-party-disband-lore", Actions.DISBAND));
            }
        }
        p.openInventory(inv);
    }
}
