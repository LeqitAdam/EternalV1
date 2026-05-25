package de.eternal.bungee;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class BungeeMessages {

    private final Map<String, Object> raw;
    private final String prefix;

    public BungeeMessages(@NotNull Map<String, Object> raw) {
        this.raw = raw;
        this.prefix = ChatColor.translateAlternateColorCodes('&', String.valueOf(raw.getOrDefault("prefix", "")));
    }

    public @NotNull String get(@NotNull String key) {
        Object v = raw.get(key);
        return v == null ? key : v.toString();
    }

    public @NotNull String format(@NotNull String key, @NotNull Object... pairs) {
        String s = get(key);
        s = s.replace("{prefix}", prefix);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = s.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        }
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public void send(@NotNull CommandSender to, @NotNull String key, @NotNull Object... pairs) {
        to.sendMessage(TextComponent.fromLegacyText(format(key, pairs)));
    }
}
