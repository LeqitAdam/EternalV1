package de.eternal.core.text;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Platform-agnostic translation lookup, factored out of the Spigot/Bungee
 * {@code Messages} classes so the party + autonicker jars don't each need their
 * own copy. Wraps a flat {@code key -> template} map (from
 * translations/&lt;lang&gt;.yml), substitutes {@code {prefix}}/{@code {placeholder}}
 * tokens, and resolves legacy {@code &}-codes to the {@code §} section sign that
 * both Bukkit and BungeeCord render natively.
 *
 * <p>Missing keys fall back to the key name itself so a broken translation file
 * never crashes the plugin.</p>
 */
public final class MessageBank {

    private final Map<String, Object> raw;
    private final String prefix;

    public MessageBank(@NotNull Map<String, Object> raw) {
        this.raw = raw;
        this.prefix = colorize(String.valueOf(raw.getOrDefault("prefix", "")));
    }

    public @NotNull String prefix() {
        return prefix;
    }

    public @NotNull String get(@NotNull String key) {
        Object v = raw.get(key);
        return v == null ? key : v.toString();
    }

    public boolean has(@NotNull String key) {
        return raw.get(key) != null;
    }

    /** Renders {@code key}: substitutes {@code {prefix}} + the supplied
     *  {@code key, value} pairs and colorizes. */
    public @NotNull String format(@NotNull String key, @NotNull Object... pairs) {
        String s = get(key).replace("{prefix}", prefix);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = s.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        }
        return colorize(s);
    }

    /** {@link #format} split on any line break — for multi-line block scalars. */
    public @NotNull List<String> lines(@NotNull String key, @NotNull Object... pairs) {
        return Arrays.asList(format(key, pairs).split("\\R"));
    }

    /**
     * Reimplements Bukkit's {@code ChatColor.translateAlternateColorCodes('&', s)}
     * with no Bukkit dependency: every {@code &} followed by a valid colour /
     * format / hex code char becomes {@code §} (and the code char is
     * lower-cased), matching vanilla behaviour exactly.
     */
    public static @NotNull String colorize(@NotNull String input) {
        char[] b = input.toCharArray();
        for (int i = 0; i < b.length - 1; i++) {
            if (b[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(b[i + 1]) > -1) {
                b[i] = '§';
                b[i + 1] = Character.toLowerCase(b[i + 1]);
            }
        }
        return new String(b);
    }
}
