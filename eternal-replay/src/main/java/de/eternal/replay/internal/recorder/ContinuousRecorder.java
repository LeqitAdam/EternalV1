package de.eternal.replay.internal.recorder;

import de.eternal.replay.model.MovementFrame;
import de.eternal.replay.model.Recordable;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The always-on recorder. Snapshots every online player's position every
 * {@link #frameIntervalTicks} ticks (default 4, i.e. 5 Hz) and maintains a
 * {@link PlayerBuffer} per UUID with a configurable retention window.
 *
 * <p>External events (hits, chat, block place/break) are pushed into the
 * same buffer by {@link RecorderListener}.</p>
 *
 * <p>Memory rough-estimate at defaults: ~150 bytes per movement frame,
 * 5 Hz, 60 s retention = 45 KB per player. With 100 online players
 * roughly 4.5 MB — well within budget.</p>
 */
public final class ContinuousRecorder {

    private final JavaPlugin plugin;
    private final long retentionMs;
    private final long sessionStartMs;
    private final int frameIntervalTicks;
    private final int inventorySnapshotIntervalTicks;
    /** Stable mapping uuid → buffer. Buffers persist across rejoins. */
    private final ConcurrentHashMap<UUID, PlayerBuffer> buffers = new ConcurrentHashMap<>();

    private int tickTask = -1;

    public ContinuousRecorder(@NotNull JavaPlugin plugin,
                              long retentionMs,
                              int frameIntervalTicks,
                              int inventorySnapshotIntervalTicks) {
        this.plugin = plugin;
        this.retentionMs = retentionMs;
        this.sessionStartMs = System.currentTimeMillis();
        this.frameIntervalTicks = Math.max(1, frameIntervalTicks);
        this.inventorySnapshotIntervalTicks = Math.max(20, inventorySnapshotIntervalTicks);
    }

    public void start() {
        if (tickTask != -1) return;
        tickTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 1L, frameIntervalTicks);
    }

    public void stop() {
        if (tickTask != -1) {
            Bukkit.getScheduler().cancelTask(tickTask);
            tickTask = -1;
        }
    }

    public @NotNull PlayerBuffer bufferOf(@NotNull UUID uuid, @NotNull String name) {
        PlayerBuffer buf = buffers.computeIfAbsent(uuid,
                u -> new PlayerBuffer(u, name, retentionMs, sessionStartMs));
        // Pull the Mojang-signed skin from the player's profile the first
        // time we see them — once captured we don't refresh, so a player
        // logging out and back in with a new skin keeps the OLD skin in
        // any replay that started while the old one was active. The
        // Replay-as-video promise (cf. user request) requires this
        // immutability.
        if (buf.skinValue().isEmpty()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) tryCaptureSkin(p, buf);
        }
        return buf;
    }

    /** Best-effort Paper-only skin lookup. We go through reflection
     *  because some Spigot forks don't ship getPlayerProfile, and we
     *  want the recorder to keep working with a default skin in that
     *  case rather than throwing. */
    private void tryCaptureSkin(@NotNull Player p, @NotNull PlayerBuffer buf) {
        try {
            var method = p.getClass().getMethod("getPlayerProfile");
            Object pp = method.invoke(p);
            var propsMethod = pp.getClass().getMethod("getProperties");
            @SuppressWarnings("unchecked")
            java.util.Set<Object> props = (java.util.Set<Object>) propsMethod.invoke(pp);
            for (Object prop : props) {
                String pName = (String) prop.getClass().getMethod("getName").invoke(prop);
                if (!"textures".equals(pName)) continue;
                String pValue = (String) prop.getClass().getMethod("getValue").invoke(prop);
                Object pSig = prop.getClass().getMethod("getSignature").invoke(prop);
                buf.adoptSkin(pValue == null ? "" : pValue,
                        pSig == null ? "" : pSig.toString());
                return;
            }
        } catch (Throwable ignored) {
            /* lookup failed — Steve skin is acceptable. */
        }
    }

    public @NotNull Collection<PlayerBuffer> allBuffers() {
        return Collections.unmodifiableCollection(buffers.values());
    }

    public long retentionMs() { return retentionMs; }

    /** Push an arbitrary event from outside (listener). */
    public void push(@NotNull UUID playerUuid, @NotNull String playerName, @NotNull Recordable event) {
        bufferOf(playerUuid, playerName).push(event);
    }

    /** Per-tick: snapshot movement for every online player. Inventory
     *  snapshots are emitted on a slower cadence. */
    private void tick() {
        long nowRel = System.currentTimeMillis() - sessionStartMs;
        boolean takeInv = (nowRel / (inventorySnapshotIntervalTicks * 50L)) !=
                ((nowRel - frameIntervalTicks * 50L) / (inventorySnapshotIntervalTicks * 50L));
        // playerIdx is local to the buffer (the buffer is per-player anyway,
        // so we always pass 0; the writer assigns proper indices when
        // assembling multi-player files).
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerBuffer buf = bufferOf(p.getUniqueId(), p.getName());
            int rel = buf.currentRelativeMs();
            String mainHand = p.getInventory().getItemInMainHand().getType().getKey().toString();
            buf.push(new MovementFrame(
                    rel, 0,
                    p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ(),
                    p.getLocation().getYaw(), p.getLocation().getPitch(),
                    p.isOnGround(), p.isSneaking(), p.isSprinting(),
                    p.isFlying(), p.isSwimming(),
                    mainHand
            ));
            if (takeInv) InventoryCodec.snapshot(buf, p);
        }
    }
}
