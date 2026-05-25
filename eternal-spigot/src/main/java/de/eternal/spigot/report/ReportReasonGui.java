package de.eternal.spigot.report;

import de.eternal.core.config.ReasonsConfig;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Chest-GUI for {@code /report <player>} — one PAPER item per report reason,
 * click to submit. Identification via {@link Holder} so locale-switching
 * the title doesn't break the click handler.
 *
 * <p>Per slot we stash the target UUID/name and the reason id into the
 * item's PersistentDataContainer so the listener can reconstruct the
 * report without re-querying anything.</p>
 */
public final class ReportReasonGui {

    public static final class Holder implements InventoryHolder {
        private Inventory inv;
        @Override public @NotNull Inventory getInventory() { return inv; }
        void setInventory(@NotNull Inventory inv) { this.inv = inv; }
    }

    private final EternalSpigot plugin;
    private final NamespacedKey targetUuidKey;
    private final NamespacedKey targetNameKey;
    private final NamespacedKey reasonIdKey;
    private final NamespacedKey reasonLabelKey;

    public ReportReasonGui(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
        this.targetUuidKey  = new NamespacedKey(plugin, "report_target_uuid");
        this.targetNameKey  = new NamespacedKey(plugin, "report_target_name");
        this.reasonIdKey    = new NamespacedKey(plugin, "report_reason_id");
        this.reasonLabelKey = new NamespacedKey(plugin, "report_reason_label");
    }

    public @NotNull NamespacedKey targetUuidKey()  { return targetUuidKey; }
    public @NotNull NamespacedKey targetNameKey()  { return targetNameKey; }
    public @NotNull NamespacedKey reasonIdKey()    { return reasonIdKey; }
    public @NotNull NamespacedKey reasonLabelKey() { return reasonLabelKey; }

    public void open(@NotNull Player reporter, @NotNull UUID targetUuid, @NotNull String targetName) {
        List<ReasonsConfig.ReportReason> reasons = plugin.reasons().reportReasons();
        if (reasons.isEmpty()) {
            plugin.messages().send(reporter, "unknown-reason", "id", "none-configured");
            return;
        }
        Holder holder = new Holder();
        int size = Math.min(54, Math.max(9, ((reasons.size() + 8) / 9) * 9));
        String title = ChatColor.translateAlternateColorCodes('&',
                plugin.messages().format("gui-report-reasons-title", "target", targetName));
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);

        int i = 0;
        for (ReasonsConfig.ReportReason r : reasons) {
            if (i >= size) break;
            inv.setItem(i++, itemFor(r, targetUuid, targetName));
        }
        reporter.openInventory(inv);
    }

    private @NotNull ItemStack itemFor(@NotNull ReasonsConfig.ReportReason r,
                                       @NotNull UUID targetUuid, @NotNull String targetName) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                plugin.messages().format("gui-report-reasons-name", "label", r.label())));
        meta.setLore(java.util.List.of(
                ChatColor.translateAlternateColorCodes('&',
                        plugin.messages().get("gui-report-reasons-lore-click"))));
        var pdc = meta.getPersistentDataContainer();
        pdc.set(targetUuidKey,  PersistentDataType.STRING, targetUuid.toString());
        pdc.set(targetNameKey,  PersistentDataType.STRING, targetName);
        pdc.set(reasonIdKey,    PersistentDataType.STRING, r.id());
        pdc.set(reasonLabelKey, PersistentDataType.STRING, r.label());
        item.setItemMeta(meta);
        return item;
    }

    public @NotNull Optional<Submission> readClick(@NotNull ItemStack clicked) {
        if (clicked.getItemMeta() == null) return Optional.empty();
        var pdc = clicked.getItemMeta().getPersistentDataContainer();
        String uuidStr = pdc.get(targetUuidKey, PersistentDataType.STRING);
        String name    = pdc.get(targetNameKey, PersistentDataType.STRING);
        String rid     = pdc.get(reasonIdKey, PersistentDataType.STRING);
        String label   = pdc.get(reasonLabelKey, PersistentDataType.STRING);
        if (uuidStr == null || name == null || rid == null || label == null) return Optional.empty();
        try {
            return Optional.of(new Submission(UUID.fromString(uuidStr), name, rid, label));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public record Submission(@NotNull UUID targetUuid, @NotNull String targetName,
                              @NotNull String reasonId, @NotNull String reasonLabel) {
    }
}
