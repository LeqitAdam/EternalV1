package de.eternal.spigot.report;

import com.google.gson.Gson;
import de.eternal.core.model.PlayerProfile;
import de.eternal.core.model.ReportEntry;
import de.eternal.spigot.Components;
import de.eternal.spigot.EternalSpigot;
import de.eternal.spigot.Tiers;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;

/**
 * Central place for "do the thing" report actions used by both the GUI and
 * the chat-button command. Handles the full claim/take-over/close/teleport
 * lifecycle plus the cross-server hop via {@link BungeeChannelBridge}.
 */
public final class ReportActions {

    private static final Gson GSON = new Gson();

    private final EternalSpigot plugin;
    private final BungeeChannelBridge bungee;

    public ReportActions(@NotNull EternalSpigot plugin, @NotNull BungeeChannelBridge bungee) {
        this.plugin = plugin;
        this.bungee = bungee;
    }

    public void claim(@NotNull Player mod, long reportId) {
        // Wer einen Report annimmt, ist implizit auf Dienst — auto-Login.
        if (plugin.staff().login(mod.getUniqueId())) {
            plugin.messages().send(mod, "reportsystem-login");
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<ReportEntry> maybe = plugin.reports().find(reportId);
            if (maybe.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.messages().send(mod, "reportsystem-not-found"));
                return;
            }
            ReportEntry r = maybe.get();
            Bukkit.getScheduler().runTask(plugin, () -> dispatchClaim(mod, r));
        });
    }

    public void close(@NotNull Player mod, long reportId, @NotNull String resolution) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = plugin.reports().close(reportId, resolution);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ok) plugin.messages().send(mod, "reportsystem-closed", "id", reportId);
                else plugin.messages().send(mod, "reportsystem-not-found");
            });
        });
    }

    public void teleportOnly(@NotNull Player mod, long reportId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<ReportEntry> maybe = plugin.reports().find(reportId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (maybe.isEmpty()) plugin.messages().send(mod, "reportsystem-not-found");
                else teleport(mod, maybe.get());
            });
        });
    }

    /* --- dispatch ----------------------------------------------------- */

    private void dispatchClaim(@NotNull Player mod, @NotNull ReportEntry r) {
        switch (r.status()) {
            case OPEN -> claimFresh(mod, r);
            case CLAIMED -> {
                if (r.handlerUuid() != null && r.handlerUuid().equals(mod.getUniqueId())) {
                    sendWithTpButton(mod, r, "reportsystem-already-yours");
                    teleport(mod, r);
                } else {
                    handleTakeOver(mod, r);
                }
            }
            case CLOSED -> plugin.messages().send(mod, "reportsystem-already-closed", "id", r.id());
        }
    }

    private void claimFresh(@NotNull Player mod, @NotNull ReportEntry r) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = plugin.reports().claim(r.id(), mod.getUniqueId(), mod.getName());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!ok) {
                    // Race: somebody beat us. Re-fetch and dispatch again.
                    claim(mod, r.id());
                    return;
                }
                plugin.messages().send(mod, "reportsystem-claimed", "id", r.id());
                teleport(mod, r);
            });
        });
    }

    private void handleTakeOver(@NotNull Player mod, @NotNull ReportEntry r) {
        int modTier = Tiers.of(mod);
        int handlerTier = r.handlerUuid() == null
                ? 0
                : plugin.storage().findProfile(r.handlerUuid())
                        .map(PlayerProfile::lastTier).orElse(0);

        if (modTier <= handlerTier) {
            // Not allowed to take over — show info + TP button.
            sendWithTpButton(mod, r, "reportsystem-already-claimed-by");
            return;
        }

        // Allowed to take over — overwrite handler.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = plugin.reports().takeOverReport(r.id(), mod.getUniqueId(), mod.getName());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!ok) {
                    plugin.messages().send(mod, "reportsystem-not-found");
                    return;
                }
                plugin.messages().send(mod, "reportsystem-took-over",
                        "id", r.id(),
                        "handler", r.handlerName() == null ? "?" : r.handlerName());

                if (r.handlerUuid() != null) {
                    Player oldHandler = Bukkit.getPlayer(r.handlerUuid());
                    if (oldHandler != null) {
                        plugin.messages().send(oldHandler, "reportsystem-taken-over-by",
                                "id", r.id(), "handler", mod.getName());
                    }
                }
                teleport(mod, r);
            });
        });
    }

    /* --- helpers ----------------------------------------------------- */

    /** Sends a translation line followed by a clickable [TP] button. */
    private void sendWithTpButton(@NotNull Player mod, @NotNull ReportEntry r, @NotNull String key) {
        String legacy = plugin.messages().format(key,
                "id", r.id(),
                "handler", r.handlerName() == null ? "?" : r.handlerName());

        TextComponent tp = Components.legacy(plugin.messages().format("report-notify-tp"));
        tp.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/eternalreport tp " + r.id()));
        tp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(TextComponent.fromLegacyText(
                        plugin.messages().format("hover-tp-report", "id", r.id())))));

        mod.spigot().sendMessage(Components.concat(legacy + " ", tp));
    }

    /**
     * Tries local-first, then cross-server via Bungee plugin-messaging.
     * Cross-server: queues a TELEPORT action; the destination Spigot's
     * ActionPoller picks it up once the mod arrives and finishes the teleport.
     */
    private void teleport(@NotNull Player mod, @NotNull ReportEntry report) {
        Player local = Bukkit.getPlayer(report.targetUuid());
        if (local != null) {
            mod.teleport(local.getLocation());
            plugin.messages().send(mod, "reportsystem-tp-success", "target", local.getName());
            return;
        }
        String payload = GSON.toJson(Map.of(
                "kind", "teleport-to-report",
                "reportId", report.id(),
                "targetUuid", report.targetUuid().toString(),
                "targetName", report.targetName()
        ));
        plugin.storage().queueAction("TELEPORT", mod.getUniqueId(), payload);
        bungee.askPlayerServer(mod, report.targetName(), targetServer -> {
            if (targetServer == null) {
                plugin.messages().send(mod, "reportsystem-tp-offline");
                return;
            }
            plugin.messages().send(mod, "reportsystem-tp-cross-server", "server", targetServer);
            bungee.connect(mod, targetServer);
        });
    }
}
