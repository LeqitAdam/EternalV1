package de.eternal.core.base;

import de.eternal.core.model.Loc;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the base-system feature (player homes, server warps, spawn).
 * Implemented by {@link de.eternal.core.storage.sql.SqlStorage}, mirroring the
 * {@link de.eternal.core.social.SocialStorage} / permission split.
 *
 * <p>Everything is scoped by {@code server} (the configured server-name) so a
 * shared network database keeps each backend's homes/warps/spawn separate
 * while still living in one place. Reads never return {@code null}.</p>
 */
public interface BaseStorage {

    /* --- homes ---------------------------------------------------------- */

    /** Insert or overwrite a named home. */
    void setHome(@NotNull UUID owner, @NotNull String server, @NotNull String name, @NotNull Loc loc);

    @NotNull Optional<Loc> getHome(@NotNull UUID owner, @NotNull String server, @NotNull String name);

    boolean deleteHome(@NotNull UUID owner, @NotNull String server, @NotNull String name);

    /** Home names for this player on this server, alphabetical. */
    @NotNull List<String> listHomes(@NotNull UUID owner, @NotNull String server);

    int homeCount(@NotNull UUID owner, @NotNull String server);

    /* --- warps ---------------------------------------------------------- */

    void setWarp(@NotNull String server, @NotNull String name, @NotNull Loc loc);

    @NotNull Optional<Loc> getWarp(@NotNull String server, @NotNull String name);

    boolean deleteWarp(@NotNull String server, @NotNull String name);

    @NotNull List<String> listWarps(@NotNull String server);

    /* --- spawn ---------------------------------------------------------- */

    void setSpawn(@NotNull String server, @NotNull Loc loc);

    @NotNull Optional<Loc> getSpawn(@NotNull String server);
}
