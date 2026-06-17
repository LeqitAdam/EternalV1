package de.eternal.spigot.command;

import de.eternal.spigot.EternalSpigot;
import de.eternal.spigot.Sessions;
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

/** /tpa, /tpahere, /tpaccept, /tpdeny — request-based teleport. */
public final class TpaCommand implements CommandExecutor, TabCompleter {

    private final EternalSpigot plugin;

    public TpaCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Player p = Cmd.player(plugin, sender);
        if (p == null) return true;
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "tpa" -> request(p, args, false);
            case "tpahere" -> request(p, args, true);
            case "tpaccept" -> accept(p);
            case "tpdeny" -> deny(p);
            default -> { }
        }
        return true;
    }

    private void request(Player p, String[] args, boolean here) {
        if (args.length < 1) {
            plugin.messages().send(p, "usage", "usage", here ? "/tpahere <player>" : "/tpa <player>");
            return;
        }
        Player target = Cmd.online(plugin, p, args[0]);
        if (target == null) return;
        if (target.equals(p)) {
            plugin.messages().send(p, "tpa-self");
            return;
        }
        plugin.sessions().addTpa(target.getUniqueId(), p.getUniqueId(), here, plugin.tpaExpiry());
        plugin.messages().send(p, "tpa-sent", "target", target.getName());
        plugin.messages().send(target, here ? "tpahere-received" : "tpa-received", "player", p.getName());
    }

    private void accept(Player p) {
        Sessions.Tpa req = plugin.sessions().getTpa(p.getUniqueId());
        if (req == null) {
            plugin.messages().send(p, "tpa-none");
            return;
        }
        Player from = plugin.getServer().getPlayer(req.from());
        plugin.sessions().removeTpa(p.getUniqueId());
        if (from == null) {
            plugin.messages().send(p, "tpa-expired");
            return;
        }
        if (req.here()) {
            // /tpahere: the accepting target travels to the requester.
            plugin.teleport(p, from.getLocation());
        } else {
            // /tpa: the requester travels to the accepting target.
            plugin.teleport(from, p.getLocation());
        }
        plugin.messages().send(p, "tpa-accepted");
        plugin.messages().send(from, "tpa-accepted-by", "player", p.getName());
    }

    private void deny(Player p) {
        Sessions.Tpa req = plugin.sessions().getTpa(p.getUniqueId());
        if (req == null) {
            plugin.messages().send(p, "tpa-none");
            return;
        }
        plugin.sessions().removeTpa(p.getUniqueId());
        plugin.messages().send(p, "tpa-denied");
        Player from = plugin.getServer().getPlayer(req.from());
        if (from != null) plugin.messages().send(from, "tpa-denied-by", "player", p.getName());
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        String n = command.getName().toLowerCase(Locale.ROOT);
        if ((n.equals("tpa") || n.equals("tpahere")) && args.length == 1) {
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
