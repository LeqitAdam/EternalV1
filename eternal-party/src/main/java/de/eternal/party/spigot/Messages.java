package de.eternal.party.spigot;

import de.eternal.core.text.MessageBank;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Thin Bukkit-side wrapper over the platform-agnostic {@link MessageBank}: adds
 * the {@code send(CommandSender, ...)} convenience that splits multi-line
 * values into one chat line each.
 */
public final class Messages {

    private final MessageBank bank;

    public Messages(@NotNull Map<String, Object> raw) {
        this.bank = new MessageBank(raw);
    }

    public @NotNull String prefix() {
        return bank.prefix();
    }

    public @NotNull String get(@NotNull String key) {
        return bank.get(key);
    }

    public boolean has(@NotNull String key) {
        return bank.has(key);
    }

    public @NotNull String format(@NotNull String key, @NotNull Object... pairs) {
        return bank.format(key, pairs);
    }

    public @NotNull List<String> lines(@NotNull String key, @NotNull Object... pairs) {
        return bank.lines(key, pairs);
    }

    public void send(@NotNull CommandSender to, @NotNull String key, @NotNull Object... pairs) {
        for (String line : bank.lines(key, pairs)) to.sendMessage(line);
    }
}
