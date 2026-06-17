package de.eternal.autonicker;

import de.eternal.core.text.MessageBank;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/** Bukkit-side wrapper over {@link MessageBank} (mirrors the party module). */
public final class Messages {

    private final MessageBank bank;

    public Messages(@NotNull Map<String, Object> raw) {
        this.bank = new MessageBank(raw);
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
