package de.eternal.replay.internal.recorder;

import de.eternal.replay.model.Recordable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Ring buffer of {@link Recordable} events for one player. Events older
 * than {@link #retentionMs} are dropped on every push.
 *
 * <p>Not thread-safe — every mutating call must happen on the main
 * server thread (the recorder schedules everything there).</p>
 */
public final class PlayerBuffer {

    private final UUID uuid;
    private final String name;
    private final Deque<Recordable> events = new ArrayDeque<>(2048);
    private final long retentionMs;
    private final long sessionStartMs;
    /** Mojang-signed skin "textures" property captured the first time
     *  this buffer is touched. Stored alongside events so the persisted
     *  replay file can reproduce the player's appearance later, even
     *  if they're offline or have changed their skin. Empty strings
     *  when no skin was available (default Steve). Mutable on purpose
     *  so {@link #adoptSkin} can fill them lazily. */
    private String skinValue = "";
    private String skinSignature = "";

    public PlayerBuffer(@NotNull UUID uuid, @NotNull String name, long retentionMs, long sessionStartMs) {
        this.uuid = uuid;
        this.name = name;
        this.retentionMs = retentionMs;
        this.sessionStartMs = sessionStartMs;
    }

    public @NotNull UUID uuid() { return uuid; }
    public @NotNull String name() { return name; }
    public @NotNull String skinValue() { return skinValue; }
    public @NotNull String skinSignature() { return skinSignature; }

    /** Capture the player's Mojang-signed skin once. Subsequent calls
     *  with the same value are no-ops; if a real skin already exists
     *  we don't overwrite it with empty values either (defensive
     *  against an unlucky GC of the OfflinePlayer profile object). */
    public void adoptSkin(@NotNull String value, @NotNull String signature) {
        if (value.isEmpty()) return;
        if (!this.skinValue.isEmpty()) return;
        this.skinValue = value;
        this.skinSignature = signature;
    }

    /** Push a new event, discarding anything older than retentionMs. */
    public void push(@NotNull Recordable event) {
        events.addLast(event);
        // Trim old entries off the front. relativeMs values are monotonically
        // non-decreasing, so the head is always the oldest.
        long cutoff = currentRelativeMs() - retentionMs;
        while (!events.isEmpty() && events.peekFirst().relativeMs() < cutoff) {
            events.removeFirst();
        }
    }

    /** Snapshot of every event currently in the buffer, oldest first.
     *  Used when freezing a buffer for a report capture. */
    public @NotNull List<Recordable> snapshot() {
        return new ArrayList<>(events);
    }

    public int size() { return events.size(); }

    public long sessionStartMs() { return sessionStartMs; }

    /** Helper for callers that want to stamp new events with the right
     *  relative timestamp. */
    public int currentRelativeMs() {
        return (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - sessionStartMs);
    }
}
