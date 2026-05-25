package de.eternal.spigot.report;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Tiny shim: {@code ItemStack#getItemMeta()} hands back a copy, so changes
 * only land if you call {@code setItemMeta} again. This helper folds that
 * round-trip into one call with a typed lambda.
 */
public final class ItemMetaUpdate {

    private ItemMetaUpdate() {
    }

    @SuppressWarnings("unchecked")
    public static <M extends ItemMeta> void apply(@NotNull ItemStack stack,
                                                  @NotNull Consumer<M> mutation) {
        M meta = (M) stack.getItemMeta();
        if (meta == null) return;
        mutation.accept(meta);
        stack.setItemMeta(meta);
    }
}
