package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Shared command helpers: sender coercion + player lookup with messages. */
final class Cmd {

    private Cmd() {
    }

    /** The sender as a Player, or null after sending players-only. */
    static @Nullable Player player(@NotNull EternalSpigot plugin, @NotNull CommandSender sender) {
        if (sender instanceof Player p) return p;
        plugin.messages().send(sender, "players-only");
        return null;
    }

    /** Online player by exact name, or null after sending player-not-found. */
    static @Nullable Player online(@NotNull EternalSpigot plugin, @NotNull CommandSender sender, @NotNull String name) {
        Player t = Bukkit.getPlayerExact(name);
        if (t == null) plugin.messages().send(sender, "player-not-found", "name", name);
        return t;
    }

    static boolean has(@NotNull CommandSender sender, @NotNull String perm) {
        return sender.hasPermission(perm);
    }

    /** Run DB work off the main thread. */
    static void async(@NotNull EternalSpigot plugin, @NotNull Runnable r) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
    }

    /** Bounce back to the main thread (teleports, world mutation). */
    static void sync(@NotNull EternalSpigot plugin, @NotNull Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }
}
