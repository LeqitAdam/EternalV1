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
 * Debug / admin command for the replay system. All subcommands gated by
 * the {@code eternal.replay.debug} permission.
 *
 * <ul>
 *     <li>{@code /replay status} — recorder + storage summary</li>
 *     <li>{@code /replay list} — last 10 persisted replays</li>
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
        if (!sender.hasPermission("eternal.replay.debug")) {
            sender.sendMessage("§cKeine Berechtigung.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "status" -> status(sender);
            case "list" -> list(sender);
            case "play" -> play(sender, args);
            case "exit" -> exit(sender);
            default -> sender.sendMessage("§7Nutzung: §f/replay <status|list|play <id>|exit>");
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

    private void list(@NotNull CommandSender s) {
        s.sendMessage("§d» §7Letzte Replays:");
        // Iterate from highest id down — store doesn't expose a list
        // method yet, so we scan 1..50 from the top.
        long top = -1;
        for (long i = 100_000; i > 0 && top < 0; i--) {
            if (api.findReplay(i).isPresent()) { top = i; break; }
        }
        if (top < 0) {
            s.sendMessage("§8 - §7Keine Replays vorhanden.");
            return;
        }
        int shown = 0;
        for (long i = top; i > 0 && shown < 10; i--) {
            api.findReplay(i).ifPresent(h -> sendRow(s, h));
            if (api.findReplay(i).isPresent()) shown++;
        }
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
