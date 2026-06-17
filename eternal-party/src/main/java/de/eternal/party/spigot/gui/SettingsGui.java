package de.eternal.party.spigot.gui;

import de.eternal.core.model.PlayerPrefs;
import de.eternal.party.spigot.EternalPartySpigot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

/** Per-player invite settings: who may friend-request / party-invite you. */
public final class SettingsGui {

    private SettingsGui() {
    }

    public static void open(@NotNull EternalPartySpigot plugin, @NotNull Player p, @NotNull PlayerPrefs prefs) {
        PartyHolder holder = new PartyHolder(PartyHolder.Type.SETTINGS);
        Inventory inv = Bukkit.createInventory(holder, 9, plugin.messages().format("gui-settings-title"));
        holder.setInventory(inv);

        String onState = plugin.messages().format("gui-state-on");
        String offState = plugin.messages().format("gui-state-off");

        inv.setItem(2, Buttons.action(plugin,
                prefs.allowFriendRequests() ? Material.LIME_DYE : Material.GRAY_DYE,
                "gui-settings-friend-requests-name", "gui-settings-friend-requests-lore",
                Actions.TOGGLE_FRIEND_REQUESTS,
                "state", prefs.allowFriendRequests() ? onState : offState));

        inv.setItem(6, Buttons.action(plugin,
                prefs.allowPartyInvites() ? Material.LIME_DYE : Material.GRAY_DYE,
                "gui-settings-party-invites-name", "gui-settings-party-invites-lore",
                Actions.TOGGLE_PARTY_INVITES,
                "state", prefs.allowPartyInvites() ? onState : offState));

        inv.setItem(8, Buttons.action(plugin, Material.ARROW, "gui-back-name", null, Actions.BACK));
        p.openInventory(inv);
    }
}
