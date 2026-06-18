package de.eternal.party.spigot.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Shared inventory holder for every party GUI. The click listener dispatches on
 * {@link #type()} (locale-safe) instead of matching titles; per-button intent is
 * carried in item PDC tags.
 */
public final class PartyHolder implements InventoryHolder {

    public enum Type { MAIN, FRIENDS, MEMBERS, SETTINGS }

    private final Type type;
    private Inventory inventory;

    public PartyHolder(@NotNull Type type) {
        this.type = type;
    }

    public @NotNull Type type() {
        return type;
    }

    void setInventory(@NotNull Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
