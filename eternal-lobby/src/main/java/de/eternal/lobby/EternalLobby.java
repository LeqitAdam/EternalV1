package de.eternal.lobby;

import de.eternal.lobby.command.LobbyCommands;
import de.eternal.lobby.cosmetic.Trails;
import de.eternal.lobby.gui.CosmeticsGui;
import de.eternal.lobby.gui.NavigatorGui;
import de.eternal.lobby.listener.LobbyListener;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Standalone lobby plugin (Spigot-only): navigator compass → server selector,
 * cosmetic particle trails ("boots"), double-jump, lobby protection + join
 * setup. No database — the cosmetic choice lives in the player PDC.
 */
public final class EternalLobby extends JavaPlugin {

    private static final String BUNGEE_CHANNEL = "BungeeCord";

    private NamespacedKey navKey;
    private NamespacedKey cosmeticsItemKey;
    private Trails trails;
    private NavigatorGui navigatorGui;
    private CosmeticsGui cosmeticsGui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.navKey = new NamespacedKey(this, "lobby_navigator");
        this.cosmeticsItemKey = new NamespacedKey(this, "lobby_cosmetics");

        this.trails = new Trails(this);
        this.trails.start();
        this.navigatorGui = new NavigatorGui(this);
        this.cosmeticsGui = new CosmeticsGui(this);

        getServer().getPluginManager().registerEvents(new LobbyListener(this), this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, BUNGEE_CHANNEL);

        LobbyCommands commands = new LobbyCommands(this);
        bind("lobby", commands);
        bind("setlobbyspawn", commands);
        bind("navigator", commands);
        bind("cosmetics", commands);

        getLogger().info("EternalLobby aktiv.");
    }

    @Override
    public void onDisable() {
        if (trails != null) trails.stop();
    }

    private void bind(@NotNull String name, @NotNull org.bukkit.command.CommandExecutor exec) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command " + name + " fehlt in plugin.yml");
            return;
        }
        cmd.setExecutor(exec);
        if (exec instanceof org.bukkit.command.TabCompleter tc) cmd.setTabCompleter(tc);
    }

    /* --- accessors --------------------------------------------------- */
    public @NotNull NamespacedKey navKey() { return navKey; }
    public @NotNull NamespacedKey cosmeticsItemKey() { return cosmeticsItemKey; }
    public @NotNull Trails trails() { return trails; }
    public @NotNull NavigatorGui navigatorGui() { return navigatorGui; }
    public @NotNull CosmeticsGui cosmeticsGui() { return cosmeticsGui; }

    /* --- spawn ------------------------------------------------------- */

    /** The configured lobby spawn, or null when none is set (empty world). */
    public @Nullable Location spawn() {
        String worldName = getConfig().getString("spawn.world", "");
        if (worldName == null || worldName.isEmpty()) return null;
        World world = getServer().getWorld(worldName);
        if (world == null) return null;
        return new Location(world,
                getConfig().getDouble("spawn.x"), getConfig().getDouble("spawn.y"), getConfig().getDouble("spawn.z"),
                (float) getConfig().getDouble("spawn.yaw"), (float) getConfig().getDouble("spawn.pitch"));
    }

    public void setSpawn(@NotNull Location loc) {
        getConfig().set("spawn.world", loc.getWorld() == null ? "" : loc.getWorld().getName());
        getConfig().set("spawn.x", loc.getX());
        getConfig().set("spawn.y", loc.getY());
        getConfig().set("spawn.z", loc.getZ());
        getConfig().set("spawn.yaw", (double) loc.getYaw());
        getConfig().set("spawn.pitch", (double) loc.getPitch());
        saveConfig();
    }

    /** Sends the player to another BungeeCord server via the plugin channel. */
    public void connect(@NotNull Player player, @NotNull String server) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            out.writeUTF("Connect");
            out.writeUTF(server);
            player.sendPluginMessage(this, BUNGEE_CHANNEL, b.toByteArray());
        } catch (IOException ex) {
            getLogger().warning("connect(" + server + ") failed: " + ex.getMessage());
        }
    }
}
