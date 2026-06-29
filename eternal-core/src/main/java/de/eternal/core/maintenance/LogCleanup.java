package de.eternal.core.maintenance;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Deletes old server log files. Platform-agnostic (pure NIO) so the Spigot
 * backend and the BungeeCord proxy share it — each just schedules
 * {@link #sweep} on its own scheduler.
 *
 * <p>Safety: only files in the configured directory whose name matches one of
 * the configured globs AND are older than {@code max-age-days} are removed;
 * names in {@code keep} (e.g. {@code latest.log}) are never touched.</p>
 */
public final class LogCleanup {

    private LogCleanup() {
    }

    /** Resolves the log directory relative to the process working directory
     *  (the server root for both Spigot and BungeeCord). */
    public static @NotNull Path resolveDir(@NotNull LogCleanupConfig cfg) {
        return Path.of(cfg.directory()).toAbsolutePath();
    }

    /** Deletes matching log files older than the configured age. Returns how
     *  many were removed. Never throws — storage/IO hiccups are logged. */
    public static int sweep(@NotNull LogCleanupConfig cfg, @NotNull Logger log) {
        if (!cfg.enabled()) return 0;
        Path dir = resolveDir(cfg);
        if (!Files.isDirectory(dir)) return 0;
        long cutoff = System.currentTimeMillis() - cfg.maxAgeDays() * 86_400_000L;
        int deleted = 0;
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (!Files.isRegularFile(p)) continue;
                if (cfg.keep().contains(name)) continue;
                if (!matchesAny(p, cfg.patterns())) continue;
                FileTime mtime;
                try {
                    mtime = Files.getLastModifiedTime(p);
                } catch (IOException ex) {
                    continue;
                }
                if (mtime.toMillis() > cutoff) continue; // still within retention
                try {
                    Files.delete(p);
                    deleted++;
                } catch (IOException ex) {
                    log.warning("Log-Cleanup: konnte " + name + " nicht loeschen: " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.warning("Log-Cleanup: Verzeichnis " + dir + " nicht lesbar: " + ex.getMessage());
            return deleted;
        }
        if (deleted > 0) {
            log.info("Log-Cleanup: " + deleted + " Log-Datei(en) aelter als "
                    + cfg.maxAgeDays() + " Tage geloescht (" + dir + ").");
        }
        return deleted;
    }

    private static boolean matchesAny(@NotNull Path file, @NotNull List<String> globs) {
        for (String g : globs) {
            try {
                if (file.getFileSystem().getPathMatcher("glob:" + g).matches(file.getFileName())) return true;
            } catch (Exception ignored) {
                // bad glob in config — skip it
            }
        }
        return false;
    }
}
