package de.eternal.replay;

import de.eternal.replay.api.ReplayApi;
import de.eternal.replay.internal.ReplayApiImpl;
import de.eternal.replay.internal.playback.PlaybackListener;
import de.eternal.replay.internal.recorder.ContinuousRecorder;
import de.eternal.replay.internal.recorder.RecorderListener;
import de.eternal.replay.internal.storage.ReplayStore;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Main entry for the standalone replay plugin. Wires the four moving
 * parts together (recorder, listener, storage, API), saves the default
 * config on first run, and registers {@link ReplayApi} in the Bukkit
 * ServicesManager so other plugins can pick it up via:
 *
 * <pre>{@code
 * ReplayApi api = Bukkit.getServicesManager().load(ReplayApi.class);
 * }</pre>
 */
public final class EternalReplay extends JavaPlugin {

    private ReplayApiImpl api;
    private ContinuousRecorder recorder;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        long retentionMs = Math.max(10_000L, getConfig().getLong("retention-seconds", 60) * 1000L);
        // Default 1 tick = 20 Hz, full server-tick resolution. Higher
        // is physically impossible — Minecraft only updates position
        // and friends once per tick. Operators on very busy servers
        // can bump this to 2 (=10 Hz) or 4 (=5 Hz) to halve / quarter
        // the RAM + disk cost per replay.
        int frameIntervalTicks = Math.max(1, getConfig().getInt("frame-interval-ticks", 1));
        int invIntervalTicks = Math.max(20, getConfig().getInt("inventory-snapshot-ticks", 100));
        long maxFollowupMs = Math.max(60_000L, getConfig().getLong("max-followup-seconds", 600) * 1000L);
        String serverName = getConfig().getString("server-name", "lobby");

        this.recorder = new ContinuousRecorder(this, retentionMs, frameIntervalTicks, invIntervalTicks);
        this.recorder.start();
        getServer().getPluginManager().registerEvents(new RecorderListener(recorder), this);

        ReplayStore store;
        try {
            store = new ReplayStore(getDataFolder().toPath().resolve("replays"));
        } catch (IOException ex) {
            getLogger().severe("Could not init replay store: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.api = new ReplayApiImpl(this, recorder, store, serverName, maxFollowupMs);
        getServer().getServicesManager().register(ReplayApi.class, api, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(
                new PlaybackListener(api, p -> api.sessionOf(p)), this);
        // /replay command for diagnostics — exposes buffer state + list of
        // persisted files, useful when verifying recording is actually
        // happening on a new install.
        var cmd = getCommand("replay");
        if (cmd != null) cmd.setExecutor(new de.eternal.replay.internal.ReplayCommand(api, recorder));

        getLogger().info("EternalReplay aktiv — retention " + (retentionMs / 1000) + "s, "
                + "frame interval " + frameIntervalTicks + " ticks");
    }

    @Override
    public void onDisable() {
        if (recorder != null) recorder.stop();
        if (api != null) {
            // Stop any active playbacks so restored inventories don't get
            // lost when the plugin unloads.
            for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) {
                if (api.isViewing(p.getUniqueId())) api.stopPlayback(p.getUniqueId());
            }
            // Persist every still-in-flight capture so a server restart
            // doesn't lose a report's recorded window.
            api.flushAllPending();
            getServer().getServicesManager().unregisterAll(this);
        }
    }

    public @NotNull ReplayApi api() { return api; }
}
