package de.eternal.party.spigot.listener;

import de.eternal.party.spigot.EternalPartySpigot;
import de.eternal.party.spigot.gui.Actions;
import de.eternal.party.spigot.gui.PartyHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Dispatches clicks in the party GUIs (holder-typed, PDC-tagged). */
public final class PartyMenuListener implements Listener {

    private final EternalPartySpigot plugin;

    public PartyMenuListener(@NotNull EternalPartySpigot plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PartyHolder)) return;
        event.setCancelled(true); // GUIs are read/act-only; never let items be taken
        if (!(event.getWhoClicked() instanceof Player p)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getItemMeta() == null) return;
        String action = clicked.getItemMeta().getPersistentDataContainer()
                .get(plugin.actionKey(), PersistentDataType.STRING);
        if (action == null) return;

        switch (action) {
            case Actions.CREATE -> { p.closeInventory(); plugin.service().createParty(p); }
            case Actions.FRIENDS -> plugin.service().openFriends(p);
            case Actions.MEMBERS -> plugin.service().openMembers(p);
            case Actions.SETTINGS -> plugin.service().openSettings(p);
            case Actions.LEAVE -> { p.closeInventory(); plugin.service().leave(p); }
            case Actions.DISBAND -> { p.closeInventory(); plugin.service().disband(p); }
            case Actions.BACK -> plugin.service().openMenu(p);
            case Actions.TOGGLE_FRIEND_REQUESTS -> plugin.service().toggleSetting(p, true);
            case Actions.TOGGLE_PARTY_INVITES -> plugin.service().toggleSetting(p, false);
            case Actions.FRIEND_ENTRY -> handleFriendEntry(event, p, clicked);
            default -> { /* unknown / display item — no-op */ }
        }
    }

    private void handleFriendEntry(@NotNull InventoryClickEvent event, @NotNull Player p, @NotNull ItemStack clicked) {
        String raw = clicked.getItemMeta().getPersistentDataContainer()
                .get(plugin.targetKey(), PersistentDataType.STRING);
        if (raw == null) return;
        UUID target;
        try {
            target = UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (event.isRightClick()) {
            plugin.service().removeFriendByUuid(p, target); // refreshes the GUI
        } else {
            p.closeInventory();
            plugin.service().inviteFriend(p, target);
        }
    }
}
