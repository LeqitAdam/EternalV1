package de.eternal.replay.internal.playback;

import de.eternal.replay.api.PlaybackHandle;
import de.eternal.replay.api.ReplayHandle;
import de.eternal.replay.internal.recorder.InventoryCodec;
import de.eternal.replay.internal.storage.ReplayCodec;
import de.eternal.replay.internal.storage.ReplayStore;
import de.eternal.replay.model.BlockEvent;
import de.eternal.replay.model.ChatEvent;
import de.eternal.replay.model.InventorySnapshot;
import de.eternal.replay.model.MovementFrame;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-viewer state while a replay is playing. Holds the ghost entities,
 * the decoded record stream, the playback clock + speed, and handles the
 * tick-by-tick advance.
 *
 * <p>State machine: PLAYING ↔ PAUSED. Seek moves the clock and forces a
 * re-scan from the file start (since the format is append-only, seeking
 * backward = reload).</p>
 */
public final class PlaybackSession {

    private final Player viewer;
    private final ReplayHandle handle;
    private final ReplayStore store;
    private final Logger log;
    private final Runnable onEnd;

    /** Saved player state, restored on exit. */
    private final ItemStack[] savedInventory;
    private final Location savedLocation;
    private final GameMode savedGameMode;
    private final boolean savedAllowFlight;
    private final boolean savedFlying;
    private final boolean savedInvulnerable;
    /** Players we hid from the viewer's view during playback. Tracked so
     *  stop() can showPlayer them again exactly once. */
    private final java.util.Set<UUID> hiddenPlayers = new java.util.HashSet<>();
    /** Plugin reference saved so stop() can pass it to showPlayer/hidePlayer
     *  (those overloads need a Plugin handle in modern Bukkit). */
    private org.bukkit.plugin.Plugin plugin;

    /** Spawned armor stands, keyed by playerIdx (matches the file header). */
    private final Map<Integer, GhostAvatar> ghosts = new HashMap<>();
    /** All decoded records in playback order. Loaded once; cheap for ~5 min. */
    private final List<ReplayCodec.DecodedRecord> records = new ArrayList<>();
    /** uuid+name lookup for spawning ghosts at first-sight. */
    private final Map<Integer, ReplayCodec.PlayerRef> players = new HashMap<>();

    /** Playback position in the relativeMs timeline. */
    private long playheadMs = 0;
    /** Speed multiplier — 1.0 = realtime, 2.0 = 2x. */
    private double speed = 1.0;
    private boolean paused = false;
    /** Index into {@link #records} for the next record to emit. */
    private int cursor = 0;
    /** When in wall-clock terms the playhead was last advanced. */
    private long lastTickMs;
    /** Spigot scheduler id of the tick task. */
    private int tickTaskId = -1;

    public PlaybackSession(@NotNull Player viewer,
                           @NotNull ReplayHandle handle,
                           @NotNull ReplayStore store,
                           @NotNull Logger log,
                           @NotNull Runnable onEnd) {
        this.viewer = viewer;
        this.handle = handle;
        this.store = store;
        this.log = log;
        this.onEnd = onEnd;
        this.savedInventory = viewer.getInventory().getContents().clone();
        this.savedLocation = viewer.getLocation().clone();
        this.savedGameMode = viewer.getGameMode();
        this.savedAllowFlight = viewer.getAllowFlight();
        this.savedFlying = viewer.isFlying();
        this.savedInvulnerable = viewer.isInvulnerable();
    }

    public @NotNull PlaybackHandle apiHandle() {
        return new PlaybackHandle(viewer.getUniqueId(), handle.id(), handle);
    }

    public void start(@NotNull org.bukkit.plugin.Plugin plugin) {
        this.plugin = plugin;
        loadAll();

        // CRITICAL FIX: every Recordable's relativeMs is stamped against
        // the recorder's session-start (= server boot time), which is
        // huge by the time a real report comes in. If we left the
        // playhead at 0 the tick loop would never emit anything until
        // wall-clock time had advanced by ~playheadOf(first record)
        // milliseconds. Anchor the playhead to the first record so frames
        // start emitting immediately.
        if (!records.isEmpty()) {
            playheadMs = records.get(0).relativeMs();
        }

        // ADVENTURE + flight instead of SPECTATOR — spectator hides the
        // hotbar (no controls visible) and gives no body to teleport.
        // Adventure prevents block changes, allow-flight lets us roam
        // freely, invulnerable stops accidental death during playback.
        viewer.setGameMode(GameMode.ADVENTURE);
        viewer.setAllowFlight(true);
        viewer.setFlying(true);
        viewer.setInvulnerable(true);

        // Hide every real player from the viewer so the scene only shows
        // ghost armor stands — otherwise the live target stands next to
        // the replay-ghost and it's impossible to tell them apart.
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(viewer.getUniqueId())) continue;
            viewer.hidePlayer(plugin, other);
            hiddenPlayers.add(other.getUniqueId());
        }

        // Anchor the viewer near the primary player's first frame so the
        // scene is visible immediately.
        records.stream()
                .filter(r -> r.type() == de.eternal.replay.model.Recordable.Type.MOVEMENT && r.playerIdx() == 0)
                .findFirst()
                .ifPresent(r -> {
                    MovementFrame mf = (MovementFrame) r.payload();
                    Location near = new Location(viewer.getWorld(), mf.x(), mf.y() + 2, mf.z(),
                            mf.yaw(), mf.pitch());
                    viewer.teleport(near);
                });
        HotbarControls.install(viewer);
        lastTickMs = System.currentTimeMillis();
        tickTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 1L, 1L);
        log.info("Playback started for replay #" + handle.id()
                + " (" + records.size() + " records, playhead starts at " + playheadMs + "ms)");
    }

    public void stop() {
        if (tickTaskId != -1) {
            Bukkit.getScheduler().cancelTask(tickTaskId);
            tickTaskId = -1;
        }
        for (GhostAvatar g : ghosts.values()) g.remove();
        ghosts.clear();
        // Restore visibility of all players we'd hidden during playback.
        if (plugin != null) {
            for (UUID uuid : hiddenPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) viewer.showPlayer(plugin, p);
            }
        }
        hiddenPlayers.clear();
        viewer.getInventory().setContents(savedInventory);
        viewer.teleport(savedLocation);
        viewer.setGameMode(savedGameMode);
        viewer.setAllowFlight(savedAllowFlight);
        viewer.setFlying(savedFlying);
        viewer.setInvulnerable(savedInvulnerable);
        onEnd.run();
    }

    public void togglePause() {
        paused = !paused;
        viewer.sendMessage(paused ? "§dReplay §8» §7Pausiert." : "§dReplay §8» §aWeiter.");
    }

    public void seek(long deltaMs) {
        // Floor at the first record's timestamp (NOT 0) — anything before
        // that has no data and would freeze the loop.
        long minMs = records.isEmpty() ? 0 : records.get(0).relativeMs();
        long maxMs = records.isEmpty() ? 0 : records.get(records.size() - 1).relativeMs();
        playheadMs = Math.max(minMs, Math.min(maxMs, playheadMs + deltaMs));
        cursor = 0;
        // Wipe ghosts so the next tick respawns them at the right frame.
        for (GhostAvatar g : ghosts.values()) g.remove();
        ghosts.clear();
        long shown = records.isEmpty() ? 0 : (playheadMs - records.get(0).relativeMs()) / 1000;
        viewer.sendMessage("§dReplay §8» §7Sprung auf §f" + shown + "s§7.");
    }

    public void changeSpeed(double delta) {
        speed = Math.max(0.25, Math.min(4.0, speed + delta));
        viewer.sendMessage("§dReplay §8» §7Geschwindigkeit §f" + String.format("%.2fx", speed));
    }

    /* ---------------------------- internals ---------------------------- */

    /** Read every record into memory once. For 5-min reports this is at
     *  most a few thousand entries — totally fine. */
    private void loadAll() {
        try (DataInputStream in = store.openForRead(handle)) {
            ReplayCodec.DecodedHeader h = ReplayCodec.readHeader(in);
            for (int i = 0; i < h.players().size(); i++) players.put(i, h.players().get(i));
            ReplayCodec.DecodedRecord r;
            while ((r = ReplayCodec.readRecord(in)) != null) records.add(r);
        } catch (IOException ex) {
            log.log(Level.WARNING, "Failed to load replay " + handle.id(), ex);
        }
    }

    /** Called every tick by the scheduler. Advances the playhead by
     *  (wallclock-delta * speed) and emits any records up to that
     *  timestamp. */
    private void tick() {
        long now = System.currentTimeMillis();
        long delta = now - lastTickMs;
        lastTickMs = now;
        if (paused) return;
        playheadMs += (long) (delta * speed);

        while (cursor < records.size() && records.get(cursor).relativeMs() <= playheadMs) {
            apply(records.get(cursor));
            cursor++;
        }
        if (cursor >= records.size()) {
            viewer.sendMessage("§dReplay §8» §7Ende erreicht.");
            stop();
        }
    }

    private void apply(@NotNull ReplayCodec.DecodedRecord r) {
        // Java 17 — classic switch on the enum + instanceof casts.
        switch (r.type()) {
            case MOVEMENT -> {
                MovementFrame mf = (MovementFrame) r.payload();
                GhostAvatar g = ghosts.computeIfAbsent(r.playerIdx(), idx -> {
                    ReplayCodec.PlayerRef ref = players.get(idx);
                    if (ref == null) return null;
                    Location loc = new Location(viewer.getWorld(), mf.x(), mf.y(), mf.z(), mf.yaw(), mf.pitch());
                    // Pass the file-recorded skin so the ghost looks the
                    // way the player did when the report was filed —
                    // even if they've changed cosmetics since. v1 files
                    // have empty strings here and the spawner falls back
                    // to a live profile lookup automatically.
                    return GhostAvatar.spawn(viewer, loc, ref.uuid(), ref.name(),
                            ref.skinValue(), ref.skinSignature());
                });
                if (g != null) g.teleport(new Location(viewer.getWorld(),
                        mf.x(), mf.y(), mf.z(), mf.yaw(), mf.pitch()));
                if (g != null && !mf.mainHandMaterial().equals("minecraft:air")) {
                    Material m = Material.matchMaterial(mf.mainHandMaterial());
                    if (m != null) g.setMainHand(new ItemStack(m));
                }
            }
            case CHAT -> {
                ChatEvent c = (ChatEvent) r.payload();
                ReplayCodec.PlayerRef ref = players.get(r.playerIdx());
                viewer.sendMessage("§7[REPLAY] §e" + (ref == null ? "?" : ref.name()) + "§8: §f" + c.message());
            }
            case BLOCK_PLACE, BLOCK_BREAK -> {
                BlockEvent be = (BlockEvent) r.payload();
                Location loc = new Location(viewer.getWorld(), be.blockX(), be.blockY(), be.blockZ());
                try {
                    if (be.placed()) {
                        viewer.sendBlockChange(loc, Bukkit.createBlockData(be.dataString()));
                    } else {
                        viewer.sendBlockChange(loc, Material.AIR.createBlockData());
                    }
                } catch (IllegalArgumentException ignored) { /* bad blockdata, skip */ }
            }
            case INVENTORY -> {
                InventorySnapshot inv = (InventorySnapshot) r.payload();
                GhostAvatar g = ghosts.get(r.playerIdx());
                if (g != null) {
                    ItemStack[] items = InventoryCodec.decode(inv.base64Data());
                    if (items.length > 0  && items[0]  != null) g.setMainHand(items[0]);
                    if (items.length > 36 && items[36] != null) g.setHelmet(items[36]);
                    if (items.length > 37 && items[37] != null) g.setChest(items[37]);
                    if (items.length > 38 && items[38] != null) g.setLeggings(items[38]);
                    if (items.length > 39 && items[39] != null) g.setBoots(items[39]);
                }
            }
            case ITEM_DROP -> {
                de.eternal.replay.model.ItemDropEvent ev = (de.eternal.replay.model.ItemDropEvent) r.payload();
                if (ev.pickedUp()) {
                    ReplayCodec.PlayerRef ref = players.get(r.playerIdx());
                    viewer.sendMessage("§7[REPLAY] §e" + (ref == null ? "?" : ref.name())
                            + " §7nahm §f" + ev.amount() + "x " + ev.material() + " §7auf.");
                    return;
                }
                try {
                    org.bukkit.Material m = org.bukkit.Material.matchMaterial(ev.material());
                    if (m == null || m == org.bukkit.Material.AIR) return;
                    Location loc = new Location(viewer.getWorld(), ev.x(), ev.y(), ev.z());
                    org.bukkit.entity.Item drop = viewer.getWorld().dropItem(loc, new ItemStack(m, ev.amount()));
                    drop.setPickupDelay(Integer.MAX_VALUE);
                    Bukkit.getScheduler().runTaskLater(plugin, drop::remove, 100L);
                } catch (IllegalArgumentException ignored) { /* bad material */ }
            }
            default -> { /* META + ITEM_USE + HIT — not visualised yet */ }
        }
    }

    public @NotNull Player viewer() { return viewer; }
    public @Nullable ReplayHandle replay() { return handle; }

    /**
     * Open a read-only chest-GUI showing the inventory snapshot for the
     * tracked player whose ghost is nearest to {@code viewer.getLocation()}
     * at the current playhead. Called by the right-click-air hotbar
     * interaction; lets the mod inspect what each player was carrying at
     * that moment without breaking the replay flow.
     */
    public void openNearestInventorySnapshot() {
        // Find the closest ghost in 3D space.
        java.util.Map.Entry<Integer, GhostAvatar> nearest = null;
        double bestSq = Double.MAX_VALUE;
        Location vp = viewer.getLocation();
        for (var e : ghosts.entrySet()) {
            // GhostAvatar has no public "location" — best-effort heuristic:
            // look up the most recent MOVEMENT frame for this player up to
            // the playhead and use that position.
            MovementFrame mf = lastMovementOf(e.getKey());
            if (mf == null) continue;
            double dx = mf.x() - vp.getX(), dy = mf.y() - vp.getY(), dz = mf.z() - vp.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestSq) { bestSq = d; nearest = e; }
        }
        if (nearest == null) {
            viewer.sendMessage("§dReplay §8» §cKein Spieler in der Nähe.");
            return;
        }
        InventorySnapshot snap = lastInventoryOf(nearest.getKey());
        if (snap == null) {
            viewer.sendMessage("§dReplay §8» §7Kein Inventar-Snapshot bisher für diesen Spieler.");
            return;
        }
        ReplayCodec.PlayerRef ref = players.get(nearest.getKey());
        String title = "§dInventar §8» §f" + (ref == null ? "?" : ref.name());
        // 5 rows = 45 slots — passt 36 main + 4 armor + 1 offhand + Lücken.
        org.bukkit.inventory.Inventory inv = org.bukkit.Bukkit.createInventory(
                new SnapshotInventoryHolder(), 45, title);
        ItemStack[] items = de.eternal.replay.internal.recorder.InventoryCodec.decode(snap.base64Data());
        for (int i = 0; i < 36 && i < items.length; i++) {
            if (items[i] != null) inv.setItem(i, items[i]);
        }
        // Rüstung in der 5. Reihe für Visualisierung (Slots 36-39).
        if (items.length > 36 && items[36] != null) inv.setItem(36, items[36]); // Helm
        if (items.length > 37 && items[37] != null) inv.setItem(37, items[37]); // Brust
        if (items.length > 38 && items[38] != null) inv.setItem(38, items[38]); // Hose
        if (items.length > 39 && items[39] != null) inv.setItem(39, items[39]); // Schuhe
        if (items.length > 40 && items[40] != null) inv.setItem(40, items[40]); // Off-hand
        viewer.openInventory(inv);
    }

    /** Marker holder so PlaybackListener's InventoryClick can identify
     *  the read-only snapshot GUI vs the viewer's own inventory. */
    public static final class SnapshotInventoryHolder implements org.bukkit.inventory.InventoryHolder {
        private org.bukkit.inventory.Inventory inv;
        @Override public @NotNull org.bukkit.inventory.Inventory getInventory() { return inv; }
    }

    private MovementFrame lastMovementOf(int playerIdx) {
        MovementFrame last = null;
        for (int i = 0; i < cursor; i++) {
            var r = records.get(i);
            if (r.type() == de.eternal.replay.model.Recordable.Type.MOVEMENT
                    && r.playerIdx() == playerIdx
                    && r.payload() instanceof MovementFrame mf) {
                last = mf;
            }
        }
        return last;
    }

    private InventorySnapshot lastInventoryOf(int playerIdx) {
        InventorySnapshot last = null;
        for (int i = 0; i < cursor; i++) {
            var r = records.get(i);
            if (r.type() == de.eternal.replay.model.Recordable.Type.INVENTORY
                    && r.playerIdx() == playerIdx
                    && r.payload() instanceof InventorySnapshot snap) {
                last = snap;
            }
        }
        return last;
    }
}
