package de.eternal.lobby.listener;

import de.eternal.lobby.EternalLobby;
import de.eternal.lobby.LobbyItems;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/** All lobby behaviour in one listener: join setup, protection, navigator +
 *  cosmetics items/GUIs, and double-jump. Everything is config-gated and the
 *  {@code eternal.lobby.bypass} permission skips protection. */
public final class LobbyListener implements Listener {

    private final EternalLobby plugin;

    public LobbyListener(@NotNull EternalLobby plugin) {
        this.plugin = plugin;
    }

    private boolean cfg(@NotNull String path) {
        return plugin.getConfig().getBoolean(path, true);
    }

    private boolean bypass(@NotNull Player p) {
        return p.hasPermission("eternal.lobby.bypass");
    }

    /* --- join setup -------------------------------------------------- */

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Location spawn = plugin.spawn();
        if (spawn != null && cfg("join.teleport-spawn")) p.teleport(spawn);
        if (cfg("join.adventure")) p.setGameMode(GameMode.ADVENTURE);
        if (cfg("join.heal")) {
            p.setHealth(Math.min(20.0, p.getMaxHealth()));
            p.setFoodLevel(20);
            p.setSaturation(20f);
        }
        if (cfg("join.clear-inventory")) {
            p.getInventory().clear();
            giveItems(p);
        }
        enableDoubleJump(p);
    }

    private void giveItems(@NotNull Player p) {
        if (!p.hasPermission("eternal.lobby.use")) return;
        int navSlot = clampSlot(plugin.getConfig().getInt("navigator.slot", 0));
        int cosSlot = clampSlot(plugin.getConfig().getInt("cosmetics.slot", 4));
        p.getInventory().setItem(navSlot, LobbyItems.navigator(plugin));
        p.getInventory().setItem(cosSlot, LobbyItems.cosmetics(plugin));
    }

    private static int clampSlot(int s) {
        return Math.max(0, Math.min(8, s));
    }

    /* --- navigator + cosmetics items --------------------------------- */

    @EventHandler
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!event.getAction().name().startsWith("RIGHT_CLICK")) return;
        Player p = event.getPlayer();
        var hand = p.getInventory().getItemInMainHand();
        if (LobbyItems.isNavigator(plugin, hand)) {
            event.setCancelled(true);
            plugin.navigatorGui().open(p);
        } else if (LobbyItems.isCosmetics(plugin, hand)) {
            event.setCancelled(true);
            plugin.cosmeticsGui().open(p);
        }
    }

    @EventHandler
    public void onGuiClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        String title = event.getView().getTitle();
        if (title.equals(plugin.navigatorGui().title())) {
            event.setCancelled(true);
            if (event.getRawSlot() < 0) return;
            String server = plugin.navigatorGui().serverForSlot(event.getRawSlot());
            if (server != null) {
                p.closeInventory();
                plugin.connect(p, server);
            }
        } else if (title.equals(plugin.cosmeticsGui().title())) {
            event.setCancelled(true);
            if (event.getRawSlot() < 0) return;
            String id = plugin.cosmeticsGui().idForSlot(event.getRawSlot());
            if (id != null) {
                plugin.trails().setChoice(p, id);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
                plugin.cosmeticsGui().open(p); // refresh the "active" marker
            }
        }
    }

    /* --- double-jump ------------------------------------------------- */

    private void enableDoubleJump(@NotNull Player p) {
        if (!cfg("double-jump.enabled")) return;
        if (p.getGameMode() == GameMode.ADVENTURE || p.getGameMode() == GameMode.SURVIVAL) {
            p.setAllowFlight(true);
        }
    }

    @EventHandler
    public void onToggleFlight(@NotNull PlayerToggleFlightEvent event) {
        Player p = event.getPlayer();
        if (!cfg("double-jump.enabled")) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        event.setCancelled(true);
        p.setFlying(false);
        p.setAllowFlight(false);
        double fwd = plugin.getConfig().getDouble("double-jump.forward", 1.0);
        double up = plugin.getConfig().getDouble("double-jump.up", 0.85);
        Vector v = p.getLocation().getDirection().setY(0).normalize().multiply(fwd).setY(up);
        p.setVelocity(v);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.6f, 1.2f);
    }

    @EventHandler
    public void onMove(@NotNull PlayerMoveEvent event) {
        Player p = event.getPlayer();
        // Re-arm double-jump once back on the ground.
        if (cfg("double-jump.enabled") && !p.getAllowFlight() && p.isOnGround()
                && (p.getGameMode() == GameMode.ADVENTURE || p.getGameMode() == GameMode.SURVIVAL)) {
            p.setAllowFlight(true);
        }
        // Void teleport — fall back to spawn instead of dying.
        if (cfg("protection.void-teleport") && event.getTo() != null && event.getTo().getY() < 0) {
            Location spawn = plugin.spawn();
            if (spawn != null) p.teleport(spawn);
        }
    }

    /* --- protection -------------------------------------------------- */

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(@NotNull BlockBreakEvent e) {
        if (cfg("protection.no-block-edit") && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(@NotNull BlockPlaceEvent e) {
        if (cfg("protection.no-block-edit") && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(@NotNull EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (cfg("protection.no-damage") && !bypass(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(@NotNull FoodLevelChangeEvent e) {
        if (cfg("protection.no-hunger") && !(e.getEntity() instanceof Player p && bypass(p))) {
            e.setCancelled(true);
            if (e.getEntity() instanceof Player p) p.setFoodLevel(20);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(@NotNull PlayerDropItemEvent e) {
        if (cfg("protection.no-drop") && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeather(@NotNull WeatherChangeEvent e) {
        if (cfg("protection.no-weather") && e.toWeatherState()) e.setCancelled(true);
    }
}
