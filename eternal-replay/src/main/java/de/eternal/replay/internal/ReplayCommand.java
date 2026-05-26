package de.eternal.replay.internal;

import de.eternal.replay.api.ReplayApi;
import de.eternal.replay.api.ReplayHandle;
import de.eternal.replay.internal.recorder.ContinuousRecorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Debug / admin command for the replay system. Permissions are split into
 * two buckets so on-duty mods can rewatch recordings without having access
 * to the recorder internals:
 *
 * <ul>
 *     <li>{@code eternal.replay.debug} — {@code status} (recorder + RAM
 *         introspection, admin-only).</li>
 *     <li>{@code eternal.replay.rewatch} — {@code list}, {@code reports},
 *         {@code play}, {@code exit} (mod-tier — needed to actually open a
 *         recorded scene after the fact).</li>
 * </ul>
 *
 * <p>Subcommands:</p>
 * <ul>
 *     <li>{@code /replay status} — recorder + storage summary (debug-perm)</li>
 *     <li>{@code /replay list [limit]} — most recent N replays of any kind</li>
 *     <li>{@code /replay reports [limit]} — most recent N report-replays only</li>
 *     <li>{@code /replay play &lt;id&gt;} — play a specific replay</li>
 *     <li>{@code /replay exit} — leave the current playback</li>
 * </ul>
 */
public final class ReplayCommand implements CommandExecutor {

    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ReplayApi api;
    private final ContinuousRecorder recorder;

    public ReplayCommand(@NotNull ReplayApi api, @NotNull ContinuousRecorder recorder) {
        this.api = api;
        this.recorder = recorder;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        // Split-permission gate: rewatch covers everything a mod needs to
        // review evidence; debug is reserved for recorder internals only.
        boolean isRewatchSub = sub.equals("list") || sub.equals("reports")
                || sub.equals("play") || sub.equals("exit");
        boolean isDebugSub = sub.equals("status");
        if (isDebugSub && !sender.hasPermission("eternal.replay.debug")) {
            sender.sendMessage("§cKeine Berechtigung §8(§7eternal.replay.debug§8)§c.");
            return true;
        }
        if (isRewatchSub && !sender.hasPermission("eternal.replay.rewatch")) {
            sender.sendMessage("§cKeine Berechtigung §8(§7eternal.replay.rewatch§8)§c.");
            return true;
        }
        switch (sub) {
            case "status" -> status(sender);
            case "list" -> list(sender, args);
            case "reports" -> listReports(sender, args);
            case "play" -> play(sender, args);
            case "exit" -> exit(sender);
            default -> sender.sendMessage("§7Nutzung: §f/replay <status|list|reports|play <id>|exit>");
        }
        return true;
    }

    private void status(@NotNull CommandSender s) {
        s.sendMessage("§d» §7EternalReplay Status");
        s.sendMessage("§d» §7Aktive Buffer§8: §f" + recorder.allBuffers().size());
        long totalEvents = recorder.allBuffers().stream().mapToLong(b -> b.size()).sum();
        s.sendMessage("§d» §7Events im RAM§8: §f" + totalEvents);
        recorder.allBuffers().forEach(b ->
                s.sendMessage("§8 - §e" + b.name() + " §8(§7" + b.size() + " Events§8)"));
    }

    private void list(@NotNull CommandSender s, @NotNull String[] args) {
        int limit = parseLimit(args, 10);
        s.sendMessage("§d» §7Letzte Replays §8(§7Top " + limit + "§8)§7:");
        java.util.List<ReplayHandle> rows = api.listLatest(limit);
        if (rows.isEmpty()) {
            s.sendMessage("§8 - §7Keine Replays vorhanden.");
            return;
        }
        for (ReplayHandle h : rows) sendRow(s, h);
    }

    /** Same shape as {@link #list} but pre-filters to report-replays so
     *  mods can quickly scan only the recordings that came from /report —
     *  avoids drowning the chat in BedWars/match captures when looking
     *  for evidence on a specific player. */
    private void listReports(@NotNull CommandSender s, @NotNull String[] args) {
        int limit = parseLimit(args, 10);
        s.sendMessage("§d» §7Letzte Report-Replays §8(§7Top " + limit + "§8)§7:");
        // listLatest returns the global newest-first; we filter by kind
        // and re-cap to limit. Fetch a larger window so we don't run dry
        // when there are non-REPORT replays mixed in.
        java.util.List<ReplayHandle> all = api.listLatest(Math.max(50, limit * 5));
        java.util.List<ReplayHandle> filtered = all.stream()
                .filter(h -> h.kind() == de.eternal.replay.api.ReplayKind.REPORT)
                .limit(limit)
                .toList();
        if (filtered.isEmpty()) {
            s.sendMessage("§8 - §7Keine Report-Replays vorhanden.");
            return;
        }
        for (ReplayHandle h : filtered) sendRow(s, h);
    }

    private static int parseLimit(@NotNull String[] args, int fallback) {
        if (args.length < 2) return fallback;
        try { return Math.max(1, Math.min(100, Integer.parseInt(args[1]))); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private void sendRow(@NotNull CommandSender s, @NotNull ReplayHandle h) {
        s.sendMessage("§8» §e#" + h.id() + " §7" + h.kind() + " §8" + (h.sourceId() == null ? "" : "#" + h.sourceId())
                + " §8| §f" + h.primaryPlayerName()
                + " §8| §7" + h.durationSeconds() + "s"
                + " §8| §7" + (h.fileSizeBytes() / 1024) + " KB"
                + " §8| §7" + DATE.format(h.endedAt()));
    }

    private void play(@NotNull CommandSender s, @NotNull String[] args) {
        if (!(s instanceof Player p)) {
            s.sendMessage("§cNur Spieler.");
            return;
        }
        if (args.length < 2) { s.sendMessage("§7/replay play <id>"); return; }
        long id;
        try { id = Long.parseLong(args[1]); }
        catch (NumberFormatException ex) { s.sendMessage("§cKeine Zahl: " + args[1]); return; }
        if (api.findReplay(id).isEmpty()) { s.sendMessage("§cReplay #" + id + " nicht gefunden."); return; }
        api.play(p, id);
    }

    private void exit(@NotNull CommandSender s) {
        if (!(s instanceof Player p)) return;
        api.stopPlayback(p.getUniqueId());
    }
}
