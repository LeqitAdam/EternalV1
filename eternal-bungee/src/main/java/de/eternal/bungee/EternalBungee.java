package de.eternal.bungee;

import de.eternal.core.config.CoreConfig;
import de.eternal.core.config.ReasonsConfig;
import de.eternal.core.service.PunishmentService;
import de.eternal.core.service.ReportService;
import de.eternal.core.staff.OnlineStaffRegistry;
import de.eternal.core.storage.EternalStorage;
import de.eternal.core.storage.sql.SqlStorage;
import de.eternal.bungee.api.ApiBridge;
import de.eternal.bungee.command.BanCommand;
import de.eternal.bungee.command.EternalCommand;
import de.eternal.bungee.command.HistoryCommand;
import de.eternal.bungee.command.LookupCommand;
import de.eternal.bungee.command.ModifyCommand;
import de.eternal.bungee.command.MuteCommand;
import de.eternal.bungee.command.ResetHistoryCommand;
import de.eternal.bungee.command.ReportCommand;
import de.eternal.bungee.command.UnbanCommand;
import de.eternal.bungee.command.UnmuteCommand;
import de.eternal.bungee.listener.BungeeChatListener;
import de.eternal.bungee.listener.BungeeConnectionListener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EternalBungee extends Plugin {

    private EternalStorage storage;
    private PunishmentService punishmentService;
    private ReportService reportService;
    private CoreConfig coreConfig;
    private ReasonsConfig reasonsConfig;
    private BungeeMessages messages;
    private OnlineStaffRegistry staff;
    private ApiBridge apiBridge;
    private de.eternal.core.integration.CloudPermsAccess cloudPerms;
    private de.eternal.bungee.listener.CloudNetBridgeListener cloudNetBridge;
    private de.eternal.bungee.api.AdminActionPoller adminActionPoller;

    @Override
    public void onEnable() {
        try {
            saveDefaultFiles();
            loadEverything();
            registerCommands();
            getProxy().getPluginManager().registerListener(this, new BungeeConnectionListener(this));
            getProxy().getPluginManager().registerListener(this, new BungeeChatListener(this));
            // Cross-server staff broadcast for web-issued bans/mutes —
            // the API queues a BROADCAST action, the receiving Spigot
            // forwards it via plugin-message on this channel, and we
            // fanout to every notify-permission player on the proxy.
            getProxy().getPluginManager().registerListener(this,
                    new de.eternal.bungee.listener.StaffBroadcastListener(this));
            // Admin-action poller: applies web-driven CloudNet group
            // changes (works for offline players — CN store is central).
            this.adminActionPoller = new de.eternal.bungee.api.AdminActionPoller(this);
            this.adminActionPoller.start();
            getLogger().info("Eternal aktiv (storage=" + coreConfig.database().type() + ").");
        } catch (Exception ex) {
            getLogger().severe("Eternal konnte nicht starten: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        if (adminActionPoller != null) adminActionPoller.stop();
        if (storage != null) {
            try { storage.close(); } catch (Exception ignored) {}
        }
    }

    public void reloadEverything() {
        loadEverything();
    }

    private static final String[] BUNDLED_TRANSLATIONS = {"de", "en"};

    private void saveDefaultFiles() throws IOException {
        Files.createDirectories(getDataFolder().toPath());
        copyIfMissing("config.yml");
        copyIfMissing("reasons.yml");

        File trDir = new File(getDataFolder(), "translations");
        Files.createDirectories(trDir.toPath());
        for (String lang : BUNDLED_TRANSLATIONS) {
            File out = new File(trDir, lang + ".yml");
            if (out.exists()) continue;
            try (InputStream in = getResourceAsStream("translations/" + lang + ".yml")) {
                if (in != null) Files.copy(in, out.toPath());
            }
        }
    }

    private void copyIfMissing(@NotNull String name) throws IOException {
        File f = new File(getDataFolder(), name);
        if (f.exists()) return;
        try (InputStream in = getResourceAsStream(name)) {
            if (in == null) throw new IOException("Resource " + name + " fehlt im Plugin-JAR");
            Files.copy(in, f.toPath());
        }
    }

    private void loadEverything() {
        Path data = getDataFolder().toPath();
        Map<String, Object> cfg = readYaml(new File(getDataFolder(), "config.yml"));
        Map<String, Object> reasons = readYaml(new File(getDataFolder(), "reasons.yml"));

        this.coreConfig = CoreConfig.fromMap(cfg, data);
        // Bungee config uses "proxy-name" rather than "server-name"; expose via serverName().
        if (cfg.containsKey("proxy-name")) {
            this.coreConfig = new CoreConfig(
                    String.valueOf(cfg.get("proxy-name")),
                    coreConfig.database(),
                    coreConfig.reports(),
                    coreConfig.history());
        }
        this.reasonsConfig = ReasonsConfig.fromMap(reasons);

        String lang = String.valueOf(cfg.getOrDefault("language", "de")).toLowerCase();
        File trFile = new File(getDataFolder(), "translations/" + lang + ".yml");
        if (!trFile.exists()) trFile = new File(getDataFolder(), "translations/de.yml");
        Map<String, Object> messagesMap = trFile.exists() ? readYaml(trFile) : new LinkedHashMap<>();
        this.messages = new BungeeMessages(messagesMap);

        if (storage == null) {
            this.storage = new SqlStorage(coreConfig.database());
            this.storage.init();
            this.punishmentService = new PunishmentService(storage);
            this.reportService = new ReportService(storage);
            this.staff = new OnlineStaffRegistry();
            this.cloudPerms = new de.eternal.core.integration.CloudPermsAccess(getLogger());
            BungeeTiers.init(this.cloudPerms);

            // CloudNet bridge: only attach when CloudPerms is present. The
            // bridge translates CloudNet group membership into
            // {@code eternal.*} perms — replaces the old standalone
            // eternal-cloudnet plugin.
            if (cloudPerms.available()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cnSec = (Map<String, Object>) cfg.getOrDefault("cloudnet", new LinkedHashMap<>());
                var mapping = de.eternal.core.integration.MappingLoader.fromMap(cnSec);
                this.cloudNetBridge = new de.eternal.bungee.listener.CloudNetBridgeListener(this, cloudPerms, mapping);
                getProxy().getPluginManager().registerListener(this, this.cloudNetBridge);
                this.cloudNetBridge.applyToOnline();
                getLogger().info("CloudNet-Bruecke aktiv — Gruppen-Mapping aus config.yml geladen.");
            } else {
                getLogger().info("CloudPerms nicht erkannt — Permissions werden konventionell via Bungee/Spigot vergeben.");
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> apiSec = (Map<String, Object>) cfg.getOrDefault("api", new LinkedHashMap<>());
        Object enabledRaw = apiSec.get("enabled");
        boolean enabled = enabledRaw instanceof Boolean b ? b : false;
        this.apiBridge = new ApiBridge(new ApiBridge.Config(
                enabled,
                String.valueOf(apiSec.getOrDefault("base-url", "")),
                String.valueOf(apiSec.getOrDefault("shared-secret", ""))
        ), getLogger());
    }

    private void registerCommands() {
        var pm = getProxy().getPluginManager();
        pm.registerCommand(this, new BanCommand(this));
        pm.registerCommand(this, new UnbanCommand(this));
        pm.registerCommand(this, new MuteCommand(this));
        pm.registerCommand(this, new UnmuteCommand(this));
        pm.registerCommand(this, new ReportCommand(this));
        // /lookup, /history, /modify, /resethistory laufen jetzt ZENTRAL auf
        // Bungee — gleiche Ausgabe wie zuvor auf Spigot, aber ein Proxy-Restart
        // deployt Translations und Code-Aenderungen netzwerkweit. Spigot
        // behaelt die Versionen als Fallback fuer Single-Server-Setups,
        // Bungee greift hier zuerst und gewinnt.
        pm.registerCommand(this, new LookupCommand(this));
        pm.registerCommand(this, new HistoryCommand(this));
        pm.registerCommand(this, new ModifyCommand(this));
        pm.registerCommand(this, new ResetHistoryCommand(this));
        pm.registerCommand(this, new EternalCommand(this));
        // /reportsystem ist BEWUSST nicht auf Bungee registriert: dann
        // reicht der Proxy das Kommando an den Backend-Spigot weiter, dessen
        // GUI sich oeffnen kann. Bungee koennte keine Chest-GUI anzeigen.
    }

    private Map<String, Object> readYaml(@NotNull File file) {
        try {
            Configuration c = ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
            return toMap(c);
        } catch (IOException ex) {
            throw new RuntimeException("Konnte " + file.getName() + " nicht lesen", ex);
        }
    }

    private Map<String, Object> toMap(@NotNull Configuration c) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : c.getKeys()) out.put(key, normalize(c.get(key)));
        return out;
    }

    private Object normalize(Object v) {
        if (v instanceof Configuration sub) return toMap(sub);
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(normalize(item));
            return out;
        }
        return v;
    }

    public @NotNull EternalStorage storage() { return storage; }
    public @NotNull PunishmentService punishments() { return punishmentService; }
    public @NotNull ReportService reports() { return reportService; }
    public @NotNull CoreConfig coreConfig() { return coreConfig; }
    public @NotNull ReasonsConfig reasons() { return reasonsConfig; }
    public @NotNull BungeeMessages messages() { return messages; }
    public @NotNull OnlineStaffRegistry staff() { return staff; }
    public @NotNull ApiBridge apiBridge() { return apiBridge; }
    public @NotNull de.eternal.core.integration.CloudPermsAccess cloudPerms() { return cloudPerms; }
}
