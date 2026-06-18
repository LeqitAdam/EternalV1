package de.eternal.spigot.command;

import de.eternal.core.chatlog.ChatLogStorage;
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

/**
 * {@code /socialspy [on|off]} — toggles network-wide private-message spying for
 * the sender. State is persisted via {@link ChatLogStorage#setSocialSpy} and the
 * change is pushed to the proxy on the {@code eternal:socialspy} channel so the
 * bungee-side spy set stays in sync. Permission: {@code eternal.socialspy}.
 */
public final class SocialSpyCommand implements CommandExecutor, TabCompleter {

    private static final String PERM = "eternal.socialspy";

    private final EternalSpigot plugin;

    public SocialSpyCommand(@NotNull EternalSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Player p = Cmd.player(plugin, sender);
        if (p == null) return true;
        if (!Cmd.has(p, PERM)) {
            plugin.messages().send(p, "no-permission");
            return true;
        }

        ChatLogStorage cl = (ChatLogStorage) plugin.storage();
        String uuid = p.getUniqueId().toString();
        Boolean override = parseOverride(args);

        // DB read + write off the main thread; the toggle plugin-message and
        // the reply hop back onto the main thread (carrier must be online).
        Cmd.async(plugin, () -> {
            boolean current = cl.isSocialSpy(uuid);
            boolean next = override != null ? override : !current;
            cl.setSocialSpy(uuid, next);
            Cmd.sync(plugin, () -> {
                Player online = plugin.getServer().getPlayer(p.getUniqueId());
                Player carrier = online != null ? online : p;
                plugin.chatLogWriter().sendToggle(carrier, uuid, next);
                if (carrier.isOnline()) {
                    plugin.messages().send(carrier, next ? "socialspy-on" : "socialspy-off");
                }
            });
        });
        return true;
    }

    /** {@code on}/{@code off} (case-insensitive) forces a state; anything else flips. */
    private static @Nullable Boolean parseOverride(@NotNull String[] args) {
        if (args.length == 0) return null;
        String a = args[0].toLowerCase(Locale.ROOT);
        return switch (a) {
            case "on", "true", "enable" -> Boolean.TRUE;
            case "off", "false", "disable" -> Boolean.FALSE;
            default -> null;
        };
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new java.util.ArrayList<>();
            for (String opt : List.of("on", "off")) {
                if (opt.startsWith(pre)) out.add(opt);
            }
            return out;
        }
        return List.of();
    }
}
