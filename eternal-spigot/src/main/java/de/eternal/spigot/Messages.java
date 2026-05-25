package de.eternal.spigot;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Translation lookup. Wraps a flat key->template Map and handles the standard
 * placeholder substitution ({prefix}, {whatever}) plus the &-code -> §
 * translation that Spigot uses on the wire.
 *
 * The Map comes from translations/<language>.yml at load time. Missing keys
 * fall back to the key name itself so the plugin keeps running even with a
 * broken translation file.
 */
public final class Messages {

    private final Map<String, Object> raw;
    private final String prefix;

    public Messages(@NotNull Map<String, Object> raw) {
        this.raw = raw;
        this.prefix = ChatColor.translateAlternateColorCodes('&',
                String.valueOf(raw.getOrDefault("prefix", "")));
    }

    public @NotNull String prefix() {
        return prefix;
    }

    public @NotNull String get(@NotNull String key) {
        Object v = raw.get(key);
        return v == null ? key : v.toString();
    }

    public @NotNull String format(@NotNull String key, @NotNull Object... pairs) {
        String s = get(key).replace("{prefix}", prefix);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = s.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        }
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public void send(@NotNull CommandSender to, @NotNull String key, @NotNull Object... pairs) {
        String rendered = format(key, pairs);
        // Multi-line translation values (block scalars) get split here so the
        // sender gets one chat line per line rather than literal \n in chat.
        for (String line : rendered.split("\\R")) {
            to.sendMessage(line);
        }
    }
}
