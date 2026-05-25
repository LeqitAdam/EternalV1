package de.eternal.spigot;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.SimplePluginManager;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Bukkit registers vanilla {@code /ban}, {@code /pardon} etc. before any
 * plugin loads, so a bare {@code /ban} resolves to the vanilla command and
 * never reaches our PluginCommand. We can't fix that via plugin.yml, but we
 * can reach into the SimpleCommandMap, drop the vanilla entries, and re-bind
 * the bare aliases to our PluginCommands.
 *
 * Reflection-only — works on stock CraftBukkit/Spigot/Paper 1.13+ since the
 * internal field names have been stable. If a future Spigot rename breaks it,
 * the helper just logs a warning and the plugin continues with the
 * {@code /eternal:ban} fallback.
 */
public final class VanillaCommandOverride {

    private static final String[] TARGETS = {"ban", "pardon", "ban-ip", "pardon-ip", "mute", "unmute"};

    private VanillaCommandOverride() {
    }

    public static void apply(@NotNull Plugin plugin) {
        try {
            SimpleCommandMap map = commandMap();
            Map<String, Command> known = knownCommandsMap(map);

            for (String name : TARGETS) {
                Command existing = known.get(name);
                if (existing != null && !isOurs(existing, plugin)) {
                    existing.unregister(map);
                    known.remove(name);
                    known.remove("minecraft:" + name);
                    known.remove("bukkit:" + name);
                }
            }

            // Re-register ours under the bare names so /ban now hits us.
            rebind(plugin, map, known, "ban");
            rebind(plugin, map, known, "unban");
            rebind(plugin, map, known, "mute");
            rebind(plugin, map, known, "unmute");

            plugin.getLogger().info("Vanilla ban/mute Commands ueberschrieben.");
        } catch (Throwable t) {
            plugin.getLogger().warning("Vanilla-Override fehlgeschlagen ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + "). Nutze /eternal:ban als Fallback.");
        }
    }

    private static boolean isOurs(@NotNull Command cmd, @NotNull Plugin plugin) {
        return cmd instanceof PluginCommand pc && pc.getPlugin() == plugin;
    }

    private static void rebind(@NotNull Plugin plugin,
                                @NotNull SimpleCommandMap map,
                                @NotNull Map<String, Command> known,
                                @NotNull String name) {
        PluginCommand cmd = ((org.bukkit.plugin.java.JavaPlugin) plugin).getCommand(name);
        if (cmd == null) return;
        // Drop whatever currently holds the bare name (could be a stale entry).
        Command stale = known.get(name);
        if (stale != null && stale != cmd) {
            stale.unregister(map);
            known.remove(name);
        }
        known.put(name, cmd);
        known.put("eternal:" + name, cmd);
    }

    private static @NotNull SimpleCommandMap commandMap() throws ReflectiveOperationException {
        Field f = SimplePluginManager.class.getDeclaredField("commandMap");
        f.setAccessible(true);
        return (SimpleCommandMap) f.get(Bukkit.getPluginManager());
    }

    @SuppressWarnings("unchecked")
    private static @NotNull Map<String, Command> knownCommandsMap(@NotNull SimpleCommandMap map)
            throws ReflectiveOperationException {
        Field f = SimpleCommandMap.class.getDeclaredField("knownCommands");
        f.setAccessible(true);
        return (Map<String, Command>) f.get(map);
    }
}
