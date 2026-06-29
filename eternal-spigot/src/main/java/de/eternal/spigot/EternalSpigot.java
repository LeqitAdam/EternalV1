package de.eternal.spigot;

import de.eternal.core.config.CoreConfig;
import de.eternal.core.config.ReasonsConfig;
import de.eternal.core.integration.CloudPermsAccess;
import de.eternal.core.service.PunishmentService;
import de.eternal.core.service.ReportService;
import de.eternal.core.staff.OnlineStaffRegistry;
import de.eternal.core.storage.EternalStorage;
import de.eternal.core.storage.sql.SqlStorage;
import de.eternal.spigot.command.BanCommand;
import de.eternal.spigot.command.HistoryCommand;
import de.eternal.spigot.command.EternalCommand;
// EternalReportCommand wurde entfernt — Reports nur noch via Dashboard.
import de.eternal.spigot.command.LookupCommand;
import de.eternal.spigot.command.MuteCommand;
import de.eternal.spigot.command.ReportCommand;
// ReportSystemCommand wurde entfernt.
import de.eternal.spigot.command.UnbanCommand;
import de.eternal.spigot.command.UnmuteCommand;
import de.eternal.spigot.api.ActionPoller;
import de.eternal.spigot.api.ApiBridge;
import de.eternal.spigot.listener.ChatListener;
import de.eternal.spigot.listener.ConnectionListener;
import de.eternal.spigot.report.BungeeChannelBridge;
// ReportActions/ReportListGui/ReportGuiListener wurden entfernt — Staff-Side komplett im Dashboard.
import de.eternal.core.base.BaseStorage;
import de.eternal.core.config.AutonickerConfig;
import de.eternal.core.config.Configs;
import de.eternal.core.social.SocialStorage;
import de.eternal.spigot.command.AutonickCommand;
import de.eternal.spigot.listener.BaseListener;
import de.eternal.spigot.listener.NickItemListener;
import de.eternal.spigot.nick.NickBridge;
import de.eternal.spigot.nick.NickService;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class EternalSpigot extends JavaPlugin {

    /** Translation files we ship inside the JAR — copied on first run. */
    private static final String[] BUNDLED_TRANSLATIONS = {"de", "en"};

    private EternalStorage storage;
    private PunishmentService punishmentService;
    private ReportService reportService;
    private CoreConfig coreConfig;
    private ReasonsConfig reasonsConfig;
    private Messages messages;
    private OnlineStaffRegistry staffRegistry;
    private CloudPermsAccess cloudPerms;
    private de.eternal.spigot.listener.CloudNetBridgeListener cloudNetBridge;
    // reportGui entfernt (war für /reportsystem list — Web-only jetzt).
    private de.eternal.spigot.integration.ReplayBridge replayBridge;
    private de.eternal.spigot.report.ReportReasonGui reportReasonGui;
    private de.eternal.spigot.command.ReportCommand reportCommandRef;
    private BungeeChannelBridge bungeeBridge;
    // reportActions entfernt — Annahme/Schließen läuft via API+ActionPoller.
    private ApiBridge apiBridge;
    private ActionPoller actionPoller;
    private de.eternal.spigot.consent.ConsentService consentService;
    private de.eternal.spigot.consent.ConsentGui consentGui;
    private ConnectionListener connectionListener;
    private de.eternal.spigot.chatlog.ChatLogWriter chatLogWriter;

    // --- autonicker (merged from the old standalone EternalAutonicker plugin) ---
    private AutonickerConfig autonickerConfig;
    private NickService nickService;
    private NickBridge nickBridge;
    private NamespacedKey nickTagKey;
    private de.eternal.core.maintenance.LogCleanupConfig logCleanupConfig;
    private org.bukkit.scheduler.BukkitTask logCleanupTask;

    // --- base system (teleport, gamemode, fly, homes/warps/spawn, /sign, ...) ---
    private Sessions baseSessions;
    private int baseMaxHomes = 3;
    private int baseTpaExpiry = 60;
    private boolean baseBackOnDeath = true;
    private String baseDefaultHomeName = "home";
    private DateTimeFormatter baseDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public void onEnable() {
        try {
            saveDefaultFiles();
            loadEverything();
            registerCommands();
            VanillaCommandOverride.apply(this);
            registerListeners();
            startLogCleanup();
            getLogger().info("Eternal aktiv (storage=" + coreConfig.database().type()
                    + ", lang=" + currentLanguage() + ", cloudperms="
                    + (cloudPerms.available() ? "ja" : "nein") + ")");
        } catch (Exception ex) {
            getLogger().severe("Eternal konnte nicht starten: " + ex.getMessage());
            ex.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /** Periodic deletion of old server log files (logs/*.log.gz). The task reads
     *  the current config each run, so /eternal reload toggling works. */
    private void startLogCleanup() {
        if (logCleanupTask != null) { logCleanupTask.cancel(); logCleanupTask = null; }
        if (!logCleanupConfig.enabled()) return;
        long periodTicks = Math.max(1L, logCleanupConfig.intervalMinutes()) * 60L * 20L;
        this.logCleanupTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                () -> de.eternal.core.maintenance.LogCleanup.sweep(logCleanupConfig, getLogger()),
                20L * 10, periodTicks); // first run after ~10s, then every interval
    }

    @Override
    public void onDisable() {
        if (logCleanupTask != null) { logCleanupTask.cancel(); logCleanupTask = null; }
        if (actionPoller != null) actionPoller.stop();
        // Flush any queued chat-log entries to DB + files before the storage
        // connection is closed below.
        if (chatLogWriter != null) {
            try { chatLogWriter.stop(); } catch (Exception ignored) {}
        }
        if (cloudNetBridge != null) cloudNetBridge.detachAll();
        if (storage != null) {
            try { storage.close(); } catch (Exception ignored) {}
        }
    }

    public void reloadEverything() {
        loadEverything();
        startLogCleanup(); // pick up enabled/interval changes
    }

    /* ----------------------------------------------------------------- */
    /* Default-File handling                                              */
    /* ----------------------------------------------------------------- */

    private void saveDefaultFiles() throws IOException {
        Files.createDirectories(getDataFolder().toPath());
        if (!new File(getDataFolder(), "config.yml").exists()) saveResource("config.yml", false);
        if (!new File(getDataFolder(), "reasons.yml").exists()) saveResource("reasons.yml", false);
        // 10k-Namen-Pool fuer den Autonicker (ein Name pro Zeile).
        if (!new File(getDataFolder(), "names.txt").exists()) saveResource("names.txt", false);

        File trDir = new File(getDataFolder(), "translations");
        Files.createDirectories(trDir.toPath());
        for (String lang : BUNDLED_TRANSLATIONS) {
            File out = new File(trDir, lang + ".yml");
            if (out.exists()) continue;
            try (InputStream in = getResource("translations/" + lang + ".yml")) {
                if (in != null) Files.copy(in, out.toPath());
            }
        }
    }

    /* ----------------------------------------------------------------- */
    /* Configuration loading                                              */
    /* ----------------------------------------------------------------- */

    private String currentLanguage = "de";

    public String currentLanguage() {
        return currentLanguage;
    }

    private void loadEverything() {
        Path dataFolder = getDataFolder().toPath();
        Map<String, Object> cfgMap = readYamlMap(new File(getDataFolder(), "config.yml"));
        Map<String, Object> reasonsMap = readYamlMap(new File(getDataFolder(), "reasons.yml"));

        this.coreConfig = CoreConfig.fromMap(cfgMap, dataFolder);
        this.reasonsConfig = ReasonsConfig.fromMap(reasonsMap);

        this.currentLanguage = String.valueOf(cfgMap.getOrDefault("language", "de")).toLowerCase();
        this.messages = loadTranslation(currentLanguage);

        // Log-Cleanup config (deletes old server log files). Re-read on reload.
        this.logCleanupConfig = de.eternal.core.maintenance.LogCleanupConfig.fromMap(
                Configs.sectionOr(cfgMap, "log-cleanup"));

        // Autonicker config (merged module). Re-read on /eternal reload.
        // The 10k-name pool lives in names.txt; when present it overrides the
        // (small) name-pool from config.yml.
        Map<String, Object> autoSec = new LinkedHashMap<>(Configs.sectionOr(cfgMap, "autonicker"));
        java.util.List<String> nickNames = readNickNames();
        if (!nickNames.isEmpty()) autoSec.put("name-pool", nickNames);
        this.autonickerConfig = AutonickerConfig.fromMap(autoSec);

        // Base-system config (homes/warps/tpa). Re-read on /eternal reload.
        Map<String, Object> baseSec = Configs.sectionOr(cfgMap, "base");
        this.baseMaxHomes = Math.max(1, Configs.intOr(baseSec, "max-homes", 3));
        this.baseTpaExpiry = Math.max(5, Configs.intOr(baseSec, "tpa-expiry-seconds", 60));
        this.baseBackOnDeath = Configs.boolOr(baseSec, "back-on-death", true);
        this.baseDefaultHomeName = Configs.stringOr(baseSec, "default-home-name", "home");
        try {
            this.baseDateFormat = DateTimeFormatter.ofPattern(messages.get("date-format"));
        } catch (Exception ex) {
            this.baseDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        }

        if (storage == null) {
            this.storage = new SqlStorage(coreConfig.database());
            this.storage.init();
            this.punishmentService = new PunishmentService(storage);
            this.reportService = new ReportService(storage);
            this.staffRegistry = new OnlineStaffRegistry();
            this.cloudPerms = new CloudPermsAccess(getLogger());
            Tiers.init(this.cloudPerms);
            this.baseSessions = new Sessions();

            // Chat-log + social-spy writer. SqlStorage implements ChatLogStorage;
            // the writer batches into the DB tables + per-server flat files on an
            // async timer and carries the outgoing eternal:socialspy messages.
            this.chatLogWriter = new de.eternal.spigot.chatlog.ChatLogWriter(
                    this, (de.eternal.core.chatlog.ChatLogStorage) storage, coreConfig.chatlog());
            this.chatLogWriter.start();

            // CloudNet bridge — only attached when CloudPerms is actually
            // present. Translates CloudNet group membership into eternal.*
            // perms via PermissionAttachment so /report and friends see them.
            if (cloudPerms.available()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cnSec = (Map<String, Object>) cfgMap.getOrDefault("cloudnet", new LinkedHashMap<>());
                var mapping = de.eternal.core.integration.MappingLoader.fromMap(cnSec);
                this.cloudNetBridge = new de.eternal.spigot.listener.CloudNetBridgeListener(this, cloudPerms, mapping);
                getServer().getPluginManager().registerEvents(this.cloudNetBridge, this);
                this.cloudNetBridge.applyToOnline();
                getLogger().info("CloudNet-Bruecke aktiv — Gruppen-Mapping aus config.yml.");
            }
            this.reportReasonGui = new de.eternal.spigot.report.ReportReasonGui(this);
            this.bungeeBridge = new BungeeChannelBridge(this);
            // Bridge that lets the Bungee-side /report command open the
            // Spigot-side GUI on the player's current backend — required
            // because the Bungee proxy intercepts /report before Spigot
            // sees it, and Bungee can't open inventories itself.
            new de.eternal.spigot.report.ReportGuiRequestListener(this);
            this.replayBridge = new de.eternal.spigot.integration.ReplayBridge(getLogger());
            // Bridge to receive captureForReport(...) requests from the
            // Bungee-side report flow — the in-flight recording has to
            // start on the backend where the reportee is online, not
            // wherever the moderator happens to be when accepting later.
            new de.eternal.spigot.report.CaptureReplayRequestListener(this);

            // --- autonicker (merged) — nick state + network bridge ---
            this.nickTagKey = new NamespacedKey(this, "autonick_tag");
            this.nickBridge = new NickBridge(this); // registers the eternal:nick channel
            this.nickService = new NickService(this);
            // In network mode the proxy owns sessions; locally, clear any stale
            // session row left by a crash (the disguise is runtime-only anyway).
            if (!autonickerConfig.network()) restoreDanglingNickSessions();
            if (autonickerConfig.enabled() && autonickerConfig.giveTagOnJoin()) {
                getServer().getOnlinePlayers().forEach(p -> nickService.giveTag(p));
            }
        }

        // ApiBridge wird auch beim /eternal reload neu erzeugt — sonst klebt
        // er an der alten api.enabled-Flag vom ersten Plugin-Start.
        @SuppressWarnings("unchecked")
        Map<String, Object> apiSec = (Map<String, Object>) cfgMap.getOrDefault("api", new LinkedHashMap<>());
        Object enabledRaw = apiSec.get("enabled");
        boolean enabled = enabledRaw instanceof Boolean b ? b : false;
        this.apiBridge = new ApiBridge(new ApiBridge.Config(
                enabled,
                String.valueOf(apiSec.getOrDefault("base-url", "")),
                String.valueOf(apiSec.getOrDefault("shared-secret", "")),
                apiSec.get("poll-interval-seconds") instanceof Number n ? n.intValue() : 3
        ), getLogger());

        if (actionPoller == null) {
            this.actionPoller = new ActionPoller(this);
            this.actionPoller.start();
        }
    }

    /** Reads the autonicker name pool from {@code names.txt} (one name per line,
     *  {@code #} comments + blanks ignored). Empty list when the file is absent. */
    private java.util.List<String> readNickNames() {
        File f = new File(getDataFolder(), "names.txt");
        if (!f.exists()) return java.util.List.of();
        try {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (!s.isEmpty() && !s.startsWith("#")) out.add(s);
            }
            return out;
        } catch (IOException ex) {
            getLogger().warning("names.txt konnte nicht gelesen werden: " + ex.getMessage());
            return java.util.List.of();
        }
    }

    private @NotNull Messages loadTranslation(@NotNull String lang) {
        File f = new File(getDataFolder(), "translations/" + lang + ".yml");
        if (!f.exists()) {
            getLogger().warning("Translation '" + lang + "' nicht gefunden — fallback auf de.");
            f = new File(getDataFolder(), "translations/de.yml");
        }
        if (!f.exists()) {
            getLogger().warning("Keine Translation-Datei vorhanden — laufe mit Key-Defaults.");
            return new Messages(new LinkedHashMap<>());
        }
        return new Messages(readYamlMap(f));
    }

    private Map<String, Object> readYamlMap(@NotNull File f) {
        try (Reader r = new InputStreamReader(Files.newInputStream(f.toPath()), StandardCharsets.UTF_8)) {
            YamlConfiguration yc = YamlConfiguration.loadConfiguration(r);
            if (yc.getKeys(false).isEmpty()) return new LinkedHashMap<>();
            return toRawMap(yc);
        } catch (IOException ex) {
            throw new RuntimeException("Konnte " + f.getName() + " nicht lesen", ex);
        }
    }

    private Map<String, Object> toRawMap(@NotNull YamlConfiguration yc) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : yc.getKeys(false)) out.put(key, normalize(yc.get(key)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object normalize(Object v) {
        if (v instanceof ConfigurationSection sec) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : sec.getKeys(false)) m.put(k, normalize(sec.get(k)));
            return m;
        }
        if (v instanceof java.util.List<?> list) {
            java.util.List<Object> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) out.add(normalize(item));
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), normalize(e.getValue()));
            return out;
        }
        return v;
    }

    /* ----------------------------------------------------------------- */
    /* Wiring                                                             */
    /* ----------------------------------------------------------------- */

    private de.eternal.spigot.command.EternalTabCompleter tabCompleter;

    private void registerCommands() {
        this.tabCompleter = new de.eternal.spigot.command.EternalTabCompleter(this);
        bind("ban", new BanCommand(this));
        bind("unban", new UnbanCommand(this));
        bind("mute", new MuteCommand(this));
        bind("unmute", new UnmuteCommand(this));
        this.reportCommandRef = new ReportCommand(this);
        bind("report", reportCommandRef);
        // /reportsystem + /eternalreport sind komplett entfernt: Reports
        // werden ausschliesslich ueber das Web-Dashboard angenommen,
        // teleportiert und geschlossen. Ingame gibt es nur noch /report
        // fuer Spieler, die jemanden melden.
        bind("lookup", new LookupCommand(this));
        bind("history", new HistoryCommand(this));
        bind("resethistory", new de.eternal.spigot.command.ResetHistoryCommand(this));
        bind("modify", new de.eternal.spigot.command.ModifyCommand(this));
        bind("eternal", new EternalCommand(this));
        bind("autonick", new AutonickCommand(this));
        registerBaseCommands();
    }

    /** Base-system commands (de.eternal.spigot.command). Grouped executors handle
     *  several command names each; missing entries warn instead of aborting. */
    private void registerBaseCommands() {
        bindBase(new de.eternal.spigot.command.TeleportCommand(this), "tp", "tphere", "tppos", "top");
        bindBase(new de.eternal.spigot.command.TpaCommand(this), "tpa", "tpahere", "tpaccept", "tpdeny");
        bindBase(new de.eternal.spigot.command.GamemodeCommand(this), "gamemode");
        bindBase(new de.eternal.spigot.command.FlyCommand(this), "fly");
        bindBase(new de.eternal.spigot.command.SpeedCommand(this), "speed");
        bindBase(new de.eternal.spigot.command.StateCommand(this), "god", "heal", "feed");
        bindBase(new de.eternal.spigot.command.SpawnCommand(this), "setspawn", "spawn", "back");
        bindBase(new de.eternal.spigot.command.HomeCommand(this), "sethome", "home", "delhome", "homes");
        bindBase(new de.eternal.spigot.command.WarpCommand(this), "setwarp", "warp", "delwarp", "warps");
        bindBase(new de.eternal.spigot.command.ItemCommand(this), "hat", "repair", "more", "sign");
        bindBase(new de.eternal.spigot.command.InventoryCommand(this), "clearinventory", "enderchest", "workbench", "invsee");
        bindBase(new de.eternal.spigot.command.WorldCommand(this), "time", "day", "night", "weather");
        bindBase(new de.eternal.spigot.command.CommsCommand(this), "broadcast", "msg", "reply", "kill");
        bindBase(new de.eternal.spigot.command.VanishCommand(this), "vanish");
        bindBase(new de.eternal.spigot.command.SocialSpyCommand(this), "socialspy");
    }

    private void bind(@NotNull String name, @NotNull org.bukkit.command.CommandExecutor exec) {
        PluginCommand cmd = Objects.requireNonNull(getCommand(name), "Command " + name + " nicht in plugin.yml");
        cmd.setExecutor(exec);
        if (exec instanceof org.bukkit.command.TabCompleter tc) cmd.setTabCompleter(tc);
        else cmd.setTabCompleter(tabCompleter);
    }

    private void bindBase(@NotNull CommandExecutor exec, @NotNull String... names) {
        for (String name : names) {
            PluginCommand cmd = getCommand(name);
            if (cmd == null) {
                getLogger().warning("Base-Command " + name + " fehlt in plugin.yml");
                continue;
            }
            cmd.setExecutor(exec);
            if (exec instanceof TabCompleter tc) cmd.setTabCompleter(tc);
            else cmd.setTabCompleter(tabCompleter);
        }
    }

    private void registerListeners() {
        // Consent first so the freeze handlers are in the listener
        // chain before the actual recording/data-handling listeners.
        // The GUI is the primary prompt — chat-based /eternal accept
        // still works as a fallback (and is unreachable through the
        // Bungee proxy, which is why we moved to a click GUI in the
        // first place).
        this.consentService = new de.eternal.spigot.consent.ConsentService(this, storage);
        this.consentGui = new de.eternal.spigot.consent.ConsentGui(this);
        getServer().getPluginManager().registerEvents(
                new de.eternal.spigot.consent.ConsentGuardListener(this), this);
        getServer().getPluginManager().registerEvents(
                new de.eternal.spigot.consent.ConsentGuiListener(this, consentGui), this);

        this.connectionListener = new ConnectionListener(this);
        getServer().getPluginManager().registerEvents(connectionListener, this);
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        // ReportGuiListener entfernt — kein ingame-Reports-GUI mehr.
        getServer().getPluginManager().registerEvents(
                new de.eternal.spigot.report.ReportReasonGuiListener(this, reportReasonGui, reportCommandRef.cooldownMap()), this);
        // Base-system listener: /back death position, /god damage cancel, /vanish hide.
        getServer().getPluginManager().registerEvents(new BaseListener(this), this);
        // Chat-log capture (MONITOR): public chat + commands -> ChatLogWriter.
        getServer().getPluginManager().registerEvents(
                new de.eternal.spigot.chatlog.ChatLogListener(this), this);
        // Autonicker (merged): nametag item give/toggle/lock + quit cleanup.
        getServer().getPluginManager().registerEvents(new NickItemListener(this), this);
    }

    /** Standalone-mode startup cleanup: a leftover nick session means a crash
     *  interrupted a nick. The disguise (profile rewrite + scoreboard team) is
     *  runtime-only and already gone after the restart, and it never changed the
     *  real CloudNet group — so we only drop the stale row. */
    private void restoreDanglingNickSessions() {
        SocialStorage social = (SocialStorage) storage;
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            int n = 0;
            for (var s : social.activeNickSessions()) {
                social.endNickSession(s.uuid());
                n++;
            }
            if (n > 0) getLogger().info("Autonicker: " + n + " haengende Nick-Session(s) zurueckgesetzt.");
        });
    }

    /* ----------------------------------------------------------------- */
    /* Accessors                                                          */
    /* ----------------------------------------------------------------- */

    public @NotNull EternalStorage storage() { return storage; }
    public @NotNull PunishmentService punishments() { return punishmentService; }
    public @NotNull ReportService reports() { return reportService; }
    public @NotNull CoreConfig coreConfig() { return coreConfig; }
    public @NotNull ReasonsConfig reasons() { return reasonsConfig; }
    public @NotNull Messages messages() { return messages; }
    public @NotNull OnlineStaffRegistry staff() { return staffRegistry; }
    public @NotNull CloudPermsAccess cloudPerms() { return cloudPerms; }
    // reportGui() accessor entfernt.
    public @NotNull de.eternal.spigot.report.ReportReasonGui reportReasonGui() { return reportReasonGui; }
    // reportActions() accessor entfernt.
    public @NotNull de.eternal.spigot.integration.ReplayBridge replayBridge() { return replayBridge; }
    public @NotNull BungeeChannelBridge bungeeBridge() { return bungeeBridge; }
    public @NotNull ApiBridge apiBridge() { return apiBridge; }
    /** Nullable — only set when CloudPerms is present. The ActionPoller's
     *  PERM_REFRESH handler null-checks before re-applying. */
    public de.eternal.spigot.listener.@org.jetbrains.annotations.Nullable CloudNetBridgeListener cloudNetBridge() { return cloudNetBridge; }
    public @NotNull de.eternal.spigot.consent.ConsentService consent() { return consentService; }
    public @NotNull de.eternal.spigot.consent.ConsentGui consentGui() { return consentGui; }
    public @NotNull ConnectionListener connectionListener() { return connectionListener; }
    /** Async batched chat-log writer + outgoing eternal:socialspy helper. */
    public @NotNull de.eternal.spigot.chatlog.ChatLogWriter chatLogWriter() { return chatLogWriter; }

    /* --- autonicker accessors ---------------------------------------- */
    public @NotNull AutonickerConfig autonicker() { return autonickerConfig; }
    public @NotNull NickService nickService() { return nickService; }
    public @NotNull NickBridge nickBridge() { return nickBridge; }
    public @NotNull NamespacedKey nickTagKey() { return nickTagKey; }

    /* --- base system accessors --------------------------------------- */
    public @NotNull Sessions sessions() { return baseSessions; }
    /** Same backing {@link SqlStorage}, typed for base-system homes/warps/spawn. */
    public @NotNull BaseStorage base() { return (BaseStorage) storage; }
    public @NotNull String serverName() { return coreConfig.serverName(); }
    public int maxHomes() { return baseMaxHomes; }
    public int tpaExpiry() { return baseTpaExpiry; }
    public boolean backOnDeath() { return baseBackOnDeath; }
    public @NotNull String defaultHomeName() { return baseDefaultHomeName; }

    /** Teleport that records the player's current spot for /back first. */
    public void teleport(@NotNull Player p, @NotNull Location to) {
        baseSessions.back.put(p.getUniqueId(), p.getLocation());
        p.teleport(to);
    }

    public @NotNull String nowFormatted() {
        return baseDateFormat.format(ZonedDateTime.now(ZoneId.systemDefault()));
    }
}
