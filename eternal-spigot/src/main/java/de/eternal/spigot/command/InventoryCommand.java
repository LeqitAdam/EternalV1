package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /clearinventory, /enderchest, /workbench, /invsee. */
public final class InventoryCommand implements CommandExecutor, TabCompleter {

    private final EternalSpigot plugin;

    public InventoryCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Player p = Cmd.player(plugin, sender);
        if (p == null) return true;
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "clearinventory" -> clear(p, args);
            case "enderchest" -> {
                Player target = others(p, args, "eternal.base.enderchest.others");
                if (target != null) p.openInventory(target.getEnderChest());
            }
            case "workbench" -> p.openWorkbench(null, true);
            case "invsee" -> {
                if (args.length < 1) {
                    plugin.messages().send(p, "usage", "usage", "/invsee <player>");
                    return true;
                }
                Player target = Cmd.online(plugin, p, args[0]);
                if (target != null) p.openInventory(target.getInventory());
            }
            default -> { }
        }
        return true;
    }

    private void clear(Player p, String[] args) {
        Player target;
        if (args.length >= 1) {
            if (!Cmd.has(p, "eternal.base.clearinventory.others")) {
                plugin.messages().send(p, "no-permission");
                return;
            }
            target = Cmd.online(plugin, p, args[0]);
            if (target == null) return;
        } else {
            target = p;
        }
        target.getInventory().clear();
        if (target.equals(p)) {
            plugin.messages().send(p, "inv-cleared-self");
        } else {
            plugin.messages().send(p, "inv-cleared-other", "player", target.getName());
            plugin.messages().send(target, "inv-cleared-self");
        }
    }

    private @Nullable Player others(Player p, String[] args, String perm) {
        if (args.length >= 1) {
            if (!Cmd.has(p, perm)) {
                plugin.messages().send(p, "no-permission");
                return null;
            }
            return Cmd.online(plugin, p, args[0]);
        }
        return p;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Player pl : plugin.getServer().getOnlinePlayers()) {
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(pl.getName());
            }
            return out;
        }
        return List.of();
    }
}
