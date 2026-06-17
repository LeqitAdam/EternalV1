package de.eternal.autonicker;

import de.eternal.core.config.AutonickerConfig;
import de.eternal.core.config.Configs;
import de.eternal.core.config.CoreConfig;
import de.eternal.core.integration.CloudPermsAccess;
import de.eternal.core.model.NickSession;
import de.eternal.core.social.SocialStorage;
import de.eternal.core.storage.sql.SqlStorage;
import de.eternal.autonicker.command.AutonickCommand;
import de.eternal.autonicker.listener.NickItemListener;
import de.eternal.autonicker.nick.NickService;
import org.bukkit.NamespacedKey;
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

/**
 * Eternal Autonicker (Spigot-only). A nametag item in the hotbar toggles a
 * random name + skin disguise; with CloudNet present it also rolls a random
 * non-team rank. Nick state is persisted so a crash/restart can restore the
 * real CloudNet group (never leaving a player stuck on a random rank).
 */
public final class EternalAutonicker extends JavaPlugin {

    private static final String[] BUNDLED_TRANSLATIONS = {"de", "en"};

    private SqlStorage storageImpl;
    private SocialStorage storage;
    private AutonickerConfig config;
    private Messages messages;
    private String serverName = "lobby";
    private String currentLanguage = "de";
    private CloudPermsAccess cloudPerms;
    private NickService nickService;
    private NamespacedKey tagKey;

    @Override
    public void onEnable() {
        try {
            this.tagKey = new NamespacedKey(this, "autonick_tag");
            saveDefaultFiles();
            loadEverything();

            this.cloudPerms = new CloudPermsAccess(getLogger());
            this.nickService = new NickService(this);

            bind("autonick", new AutonickCommand(this));
            getServer().getPluginManager().registerEvents(new NickItemListener(this), this);

            restoreDanglingSessions();
            if (config.enabled() && config.giveTagOnJoin()) {
                getServer().getOnlinePlayers().forEach(p -> nickService.giveTag(p));
            }

            getLogger().info("EternalAutonicker aktiv (enabled=" + config.enabled()
                    + ", lang=" + currentLanguage + ", cloudperms=" + (cloudPerms.available() ? "ja" : "nein") + ").");
        } catch (Exception ex) {
            getLogger().severe("EternalAutonicker konnte nicht starten: " + ex.getMessage());
            ex.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (storageImpl != null) {
            try { storageImpl.close(); } catch (Exception ignored) { }
        }
    }

    /** On startup, any leftover nick session means a crash interrupted a nick:
     *  restore the player's real CloudNet group and clear the session so nobody
     *  is stuck wearing a random rank. The visual disguise is gone anyway after
     *  a reconnect. */
    private void restoreDanglingSessions() {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            int n = 0;
            for (NickSession s : storage.activeNickSessions()) {
                if (cloudPerms.available() && !s.originalGroup().isEmpty()) {
                    cloudPerms.setPrimaryGroup(s.uuid(), s.originalGroup());
                }
                storage.endNickSession(s.uuid());
                n++;
            }
            if (n > 0) getLogger().info("Autonicker: " + n + " haengende Nick-Session(s) zurueckgesetzt.");
        });
    }

    /* ----------------------------------------------------------------- */

    private void saveDefaultFiles() throws IOException {
        Files.createDirectories(getDataFolder().toPath());
        if (!new File(getDataFolder(), "config.yml").exists()) saveResource("config.yml", false);
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

    private void loadEverything() {
        Path dataFolder = getDataFolder().toPath();
        Map<String, Object> cfgMap = readYamlMap(new File(getDataFolder(), "config.yml"));
        CoreConfig core = CoreConfig.fromMap(cfgMap, dataFolder);
        this.serverName = core.serverName();
        this.config = AutonickerConfig.fromMap(Configs.sectionOr(cfgMap, "autonicker"));
        this.currentLanguage = String.valueOf(cfgMap.getOrDefault("language", "de")).toLowerCase();
        this.messages = loadTranslation(currentLanguage);
        if (storage == null) {
            this.storageImpl = new SqlStorage(core.database());
            this.storageImpl.init();
            this.storage = this.storageImpl;
        }
    }

    private @NotNull Messages loadTranslation(@NotNull String lang) {
        File f = new File(getDataFolder(), "translations/" + lang + ".yml");
        if (!f.exists()) f = new File(getDataFolder(), "translations/de.yml");
        if (!f.exists()) return new Messages(new LinkedHashMap<>());
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

    private void bind(@NotNull String name, @NotNull org.bukkit.command.CommandExecutor exec) {
        PluginCommand cmd = Objects.requireNonNull(getCommand(name), "Command " + name + " nicht in plugin.yml");
        cmd.setExecutor(exec);
        if (exec instanceof org.bukkit.command.TabCompleter tc) cmd.setTabCompleter(tc);
    }

    /* --- accessors ---------------------------------------------------- */

    public @NotNull SocialStorage storage() { return storage; }
    public @NotNull AutonickerConfig config() { return config; }
    public @NotNull Messages messages() { return messages; }
    public @NotNull CloudPermsAccess cloudPerms() { return cloudPerms; }
    public @NotNull NickService nickService() { return nickService; }
    public @NotNull NamespacedKey tagKey() { return tagKey; }
    public @NotNull String serverName() { return serverName; }
}
