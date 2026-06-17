package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/** /speed &lt;walk|fly&gt; &lt;0-10&gt; [player]. */
public final class SpeedCommand implements CommandExecutor, TabCompleter {

    private final EternalSpigot plugin;

    public SpeedCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "usage", "usage", "/speed <walk|fly> <0-10> [player]");
            return true;
        }
        String type = args[0].toLowerCase(Locale.ROOT);
        boolean fly = type.startsWith("f");
        boolean walk = type.startsWith("w");
        if (!fly && !walk) {
            plugin.messages().send(sender, "speed-invalid");
            return true;
        }
        float value;
        try {
            value = Float.parseFloat(args[1]);
        } catch (NumberFormatException ex) {
            plugin.messages().send(sender, "speed-invalid");
            return true;
        }
        if (value < 0f || value > 10f) {
            plugin.messages().send(sender, "speed-invalid");
            return true;
        }
        Player target;
        if (args.length >= 3) {
            target = Cmd.online(plugin, sender, args[2]);
            if (target == null) return true;
        } else {
            target = Cmd.player(plugin, sender);
            if (target == null) return true;
        }
        float fraction = Math.max(0f, Math.min(1f, value / 10f));
        if (fly) target.setFlySpeed(fraction);
        else target.setWalkSpeed(fraction);
        String typeName = fly ? "fly" : "walk";
        if (target.equals(sender)) {
            plugin.messages().send(sender, "speed-self", "type", typeName, "value", trim(value));
        } else {
            plugin.messages().send(sender, "speed-other", "player", target.getName(), "type", typeName, "value", trim(value));
        }
        return true;
    }

    private static String trim(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return List.of("walk", "fly");
        return List.of();
    }
}
