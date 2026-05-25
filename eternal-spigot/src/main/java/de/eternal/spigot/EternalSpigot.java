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
import de.eternal.spigot.command.EternalReportCommand;
import de.eternal.spigot.command.LookupCommand;
import de.eternal.spigot.command.MuteCommand;
import de.eternal.spigot.command.ReportCommand;
import de.eternal.spigot.command.ReportSystemCommand;
import de.eternal.spigot.command.UnbanCommand;
import de.eternal.spigot.command.UnmuteCommand;
import de.eternal.spigot.api.ActionPoller;
import de.eternal.spigot.api.ApiBridge;
import de.eternal.spigot.listener.ChatListener;
import de.eternal.spigot.listener.ConnectionListener;
import de.eternal.spigot.report.BungeeChannelBridge;
import de.eternal.spigot.report.ReportActions;
import de.eternal.spigot.report.ReportGuiListener;
import de.eternal.spigot.report.ReportListGui;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
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
    private ReportListGui reportGui;
    private de.eternal.spigot.report.ReportReasonGui reportReasonGui;
    private de.eternal.spigot.command.ReportCommand reportCommandRef;
    private BungeeChannelBridge bungeeBridge;
    private ReportActions reportActions;
    private ApiBridge apiBridge;
    private ActionPoller actionPoller;

    @Override
    public void onEnable() {
        try {
            saveDefaultFiles();
            loadEverything();
            registerCommands();
            VanillaCommandOverride.apply(this);
            registerListeners();
            getLogger().info("Eternal aktiv (storage=" + coreConfig.database().type()
                    + ", lang=" + currentLanguage() + ", cloudperms="
                    + (cloudPerms.available() ? "ja" : "nein") + ")");
        } catch (Exception ex) {
            getLogger().severe("Eternal konnte nicht starten: " + ex.getMessage());
            ex.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (actionPoller != null) actionPoller.stop();
        if (cloudNetBridge != null) cloudNetBridge.detachAll();
        if (storage != null) {
            try { storage.close(); } catch (Exception ignored) {}
        }
    }

    public void reloadEverything() {
        loadEverything();
    }

    /* ----------------------------------------------------------------- */
    /* Default-File handling                                              */
    /* ----------------------------------------------------------------- */

    private void saveDefaultFiles() throws IOException {
        Files.createDirectories(getDataFolder().toPath());
        if (!new File(getDataFolder(), "config.yml").exists()) saveResource("config.yml", false);
        if (!new File(getDataFolder(), "reasons.yml").exists()) saveResource("reasons.yml", false);

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

        if (storage == null) {
            this.storage = new SqlStorage(coreConfig.database());
            this.storage.init();
            this.punishmentService = new PunishmentService(storage);
            this.reportService = new ReportService(storage);
            this.staffRegistry = new OnlineStaffRegistry();
            this.cloudPerms = new CloudPermsAccess(getLogger());
            Tiers.init(this.cloudPerms);

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
            this.reportGui = new ReportListGui(this);
            this.reportReasonGui = new de.eternal.spigot.report.ReportReasonGui(this);
            this.bungeeBridge = new BungeeChannelBridge(this);
            this.reportActions = new ReportActions(this, bungeeBridge);
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

    private void registerCommands() {
        bind("ban", new BanCommand(this));
        bind("unban", new UnbanCommand(this));
        bind("mute", new MuteCommand(this));
        bind("unmute", new UnmuteCommand(this));
        this.reportCommandRef = new ReportCommand(this);
        bind("report", reportCommandRef);
        bind("reportsystem", new ReportSystemCommand(this));
        bind("lookup", new LookupCommand(this));
        bind("history", new HistoryCommand(this));
        bind("resethistory", new de.eternal.spigot.command.ResetHistoryCommand(this));
        bind("modify", new de.eternal.spigot.command.ModifyCommand(this));
        bind("eternal", new EternalCommand(this));
        bind("eternalreport", new EternalReportCommand(this, reportActions));
    }

    private void bind(@NotNull String name, @NotNull org.bukkit.command.CommandExecutor exec) {
        PluginCommand cmd = Objects.requireNonNull(getCommand(name), "Command " + name + " nicht in plugin.yml");
        cmd.setExecutor(exec);
        if (exec instanceof org.bukkit.command.TabCompleter tc) cmd.setTabCompleter(tc);
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new ConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new ReportGuiListener(this, reportGui, reportActions), this);
        getServer().getPluginManager().registerEvents(
                new de.eternal.spigot.report.ReportReasonGuiListener(this, reportReasonGui, reportCommandRef.cooldownMap()), this);
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
    public @NotNull ReportListGui reportGui() { return reportGui; }
    public @NotNull de.eternal.spigot.report.ReportReasonGui reportReasonGui() { return reportReasonGui; }
    public @NotNull ReportActions reportActions() { return reportActions; }
    public @NotNull BungeeChannelBridge bungeeBridge() { return bungeeBridge; }
    public @NotNull ApiBridge apiBridge() { return apiBridge; }
}
