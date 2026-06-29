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
                .map(p -> new Target(p.uuid(), p.name(), false))
                // Fallback: a punished player may have no profile row (e.g. never
                // accepted the privacy policy) — resolve them from the punishments
                // table so /unban + /unmute work for them too.
                .or(() -> storage.findRecentPunishmentByName(name)
                        .map(b -> new Target(b.targetUuid(), b.targetName(), false)))
                // Last: resolve a currently-nicked player by their FAKE name so
                // staff can /lookup, /history, /ban etc. the real player behind a
                // disguise they can see.
                .or(() -> ((de.eternal.core.social.SocialStorage) storage).findNickSessionByNickName(name)
                        .map(s -> new Target(s.uuid(), s.originalName(),
                                ProxyServer.getInstance().getPlayer(s.uuid()) != null)));
    }
}
