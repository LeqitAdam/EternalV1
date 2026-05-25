package de.eternal.bungee.command;

import de.eternal.core.storage.EternalStorage;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

public final class TargetResolver {

    public record Target(@NotNull UUID uuid, @NotNull String name, boolean online) {
    }

    private final EternalStorage storage;

    public TargetResolver(@NotNull EternalStorage storage) {
        this.storage = storage;
    }

    public @NotNull Optional<Target> resolve(@NotNull String name) {
        ProxiedPlayer online = ProxyServer.getInstance().getPlayer(name);
        if (online != null) return Optional.of(new Target(online.getUniqueId(), online.getName(), true));
        return storage.findProfileByName(name)
                .map(p -> new Target(p.uuid(), p.name(), false));
    }
}
