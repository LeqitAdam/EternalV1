package de.eternal.party.spigot;

import de.eternal.core.config.CoreConfig;
import de.eternal.core.config.Configs;
import de.eternal.core.config.PartyConfig;
import de.eternal.core.social.SocialStorage;
import de.eternal.core.storage.sql.SqlStorage;
import de.eternal.party.spigot.command.FriendCommand;
import de.eternal.party.spigot.command.PartyCommand;
import de.eternal.party.spigot.listener.PartyItemListener;
import de.eternal.party.spigot.listener.PartyMenuListener;
import de.eternal.party.spigot.net.PartyChannel;
import de.eternal.party.spigot.social.PartyService;
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
 * Spigot backend of the Eternal party + friends feature. Owns its own
 * {@link SqlStorage} (pointed at the same DB as the main Eternal plugin) so the
 * jar is self-contained, exactly like {@code eternal-api}. The proxy companion
 * ({@code eternal-party-bungee}) only relays cross-server notifications.
 */
public final class EternalPartySpigot extends JavaPlugin {

    private static final String[] BUNDLED_TRANSLATIONS = {"de", "en"};

    private SqlStorage storageImpl;
    private SocialStorage storage;
    private PartyConfig partyConfig;
    private Messages messages;
    private String serverName = "lobby";
    private String currentLanguage = "de";

    private PartyService service;

    // PDC keys — created once from this plugin instance and reused by the
    // item builder, GUIs and listeners so identification survives renames.
    private NamespacedKey headKey;
    private NamespacedKey actionKey;
    private NamespacedKey targetKey;

    @Override
    public void onEnable() {
        try {
            this.headKey = new NamespacedKey(this, "party_head");
            this.actionKey = new NamespacedKey(this, "party_action");
            this.targetKey = new NamespacedKey(this, "party_target");

            saveDefaultFiles();
            loadEverything();

            // Outgoing channel for cross-server notification delivery; the
            // BungeeCord proxy forwards "deliver" messages to the target.
            getServer().getMessenger().registerOutgoingPluginChannel(this, PartyChannel.CHANNEL);

            this.service = new PartyService(this);

            registerCommands();
            getServer().getPluginManager().registerEvents(new PartyItemListener(this), this);
            getServer().getPluginManager().registerEvents(new PartyMenuListener(this), this);

            // Hand items to players already online (e.g. /reload).
            if (partyConfig.enabled() && partyConfig.giveHeadOnJoin()) {
                getServer().getOnlinePlayers().forEach(p -> service.giveHead(p));
            }

            getLogger().info("EternalParty aktiv (storage=" + partyConfig.enabled()
                    + ", lang=" + currentLanguage + ", db=" + storageImpl.getClass().getSimpleName() + ").");
        } catch (Exception ex) {
            getLogger().severe("EternalParty konnte nicht starten: " + ex.getMessage());
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
        this.partyConfig = PartyConfig.fromMap(Configs.sectionOr(cfgMap, "party"));
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

    private void registerCommands() {
        bind("party", new PartyCommand(this));
        bind("friend", new FriendCommand(this));
    }

    private void bind(@NotNull String name, @NotNull org.bukkit.command.CommandExecutor exec) {
        PluginCommand cmd = Objects.requireNonNull(getCommand(name), "Command " + name + " nicht in plugin.yml");
        cmd.setExecutor(exec);
        if (exec instanceof org.bukkit.command.TabCompleter tc) cmd.setTabCompleter(tc);
    }

    /* --- accessors ---------------------------------------------------- */

    public @NotNull SocialStorage storage() { return storage; }
    /** Same backing {@link SqlStorage}, typed for uuid&lt;-&gt;name profile lookups. */
    public @NotNull de.eternal.core.storage.EternalStorage profiles() { return storageImpl; }
    public @NotNull PartyConfig partyConfig() { return partyConfig; }
    public @NotNull Messages messages() { return messages; }
    public @NotNull String serverName() { return serverName; }
    public @NotNull PartyService service() { return service; }
    public @NotNull NamespacedKey headKey() { return headKey; }
    public @NotNull NamespacedKey actionKey() { return actionKey; }
    public @NotNull NamespacedKey targetKey() { return targetKey; }
}
