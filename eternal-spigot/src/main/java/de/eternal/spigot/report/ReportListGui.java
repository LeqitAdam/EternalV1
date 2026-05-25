package de.eternal.spigot.report;

import de.eternal.core.model.ReportEntry;
import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chest-GUI mit allen offenen Reports als Player-Heads (Skin = Reportziel).
 * Identifizierung der GUI ueber {@link Holder} — nicht ueber den Title, damit
 * Title-Aenderungen / Locale-Switch nicht alles brechen.
 */
public final class ReportListGui {

    /** Marker holder used to recognise our inventory in click events. */
    public static final class Holder implements InventoryHolder {
        private Inventory inv;
        @Override public @NotNull Inventory getInventory() { return inv; }
        void setInventory(@NotNull Inventory inv) { this.inv = inv; }
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final EternalSpigot plugin;
    private final NamespacedKey reportIdKey;

    public ReportListGui(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
        this.reportIdKey = new NamespacedKey(plugin, "report_id");
    }

    public @NotNull NamespacedKey reportIdKey() {
        return reportIdKey;
    }

    public void open(@NotNull Player viewer) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ReportEntry> open = plugin.reports().open();
            Bukkit.getScheduler().runTask(plugin, () -> render(viewer, open));
        });
    }

    private void render(@NotNull Player viewer, @NotNull List<ReportEntry> reports) {
        if (reports.isEmpty()) {
            plugin.messages().send(viewer, "reportsystem-list-empty");
            return;
        }
        Holder holder = new Holder();
        int size = Math.min(54, Math.max(9, ((reports.size() + 8) / 9) * 9));
        String title = ChatColor.translateAlternateColorCodes('&', plugin.messages().get("gui-reports-title"));
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);

        int i = 0;
        for (ReportEntry r : reports) {
            if (i >= size) break;
            inv.setItem(i++, headFor(r));
        }
        viewer.openInventory(inv);
    }

    private @NotNull ItemStack headFor(@NotNull ReportEntry r) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMetaUpdate.apply(head, (SkullMeta meta) -> {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(r.targetUuid()));
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                    plugin.messages().format("gui-reports-name",
                            "id", r.id(),
                            "target", r.targetName())));
            List<String> lore = new ArrayList<>();
            lore.add(line("gui-reports-lore-reason", "reason", r.reasonLabel()));
            lore.add(line("gui-reports-lore-reporter", "reporter", r.reporterName()));
            lore.add(line("gui-reports-lore-server", "server", r.serverName()));
            lore.add(line("gui-reports-lore-when", "when", WHEN.format(r.createdAt())));
            if (r.comment() != null && !r.comment().isBlank()) {
                lore.add(line("gui-reports-lore-comment", "comment", r.comment()));
            }
            if (r.handlerName() != null) {
                lore.add(line("gui-reports-lore-handler", "handler", r.handlerName()));
            }
            lore.add("");
            lore.add(line("gui-reports-lore-help-claim"));
            lore.add(line("gui-reports-lore-help-close"));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(reportIdKey, PersistentDataType.LONG, r.id());
        });
        return head;
    }

    private @NotNull String line(@NotNull String key, @NotNull Object... pairs) {
        return plugin.messages().format(key, pairs);
    }

    public @NotNull Optional<Long> readReportId(@NotNull ItemStack stack) {
        if (stack.getType() != Material.PLAYER_HEAD) return Optional.empty();
        if (!stack.hasItemMeta()) return Optional.empty();
        Long id = stack.getItemMeta().getPersistentDataContainer().get(reportIdKey, PersistentDataType.LONG);
        return Optional.ofNullable(id);
    }
}
