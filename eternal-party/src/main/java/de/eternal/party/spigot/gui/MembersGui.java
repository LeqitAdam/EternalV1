package de.eternal.party.spigot.gui;

import de.eternal.core.model.PartyMember;
import de.eternal.core.text.MessageBank;
import de.eternal.party.spigot.EternalPartySpigot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Read-only list of the current party's members. */
public final class MembersGui {

    private static final int CAPACITY = 26;

    private MembersGui() {
    }

    public static void open(@NotNull EternalPartySpigot plugin, @NotNull Player p, @NotNull List<PartyMember> members) {
        PartyHolder holder = new PartyHolder(PartyHolder.Type.MEMBERS);
        Inventory inv = Bukkit.createInventory(holder, 27, plugin.messages().format("gui-members-title"));
        holder.setInventory(inv);

        int slot = 0;
        for (PartyMember m : members) {
            if (slot >= CAPACITY) break;
            String role = m.isLeader() ? "Leader" : "Member";
            String colour = m.isLeader() ? "&6" : "&f";
            inv.setItem(slot++, Buttons.displayHead(Bukkit.getOfflinePlayer(m.uuid()),
                    MessageBank.colorize(colour + m.name()),
                    plugin.messages().lines("gui-member-entry-lore", "role", role)));
        }
        inv.setItem(26, Buttons.action(plugin, Material.ARROW, "gui-back-name", null, Actions.BACK));
        p.openInventory(inv);
    }
}
