package de.eternal.core.staff;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OnlineStaffRegistry {

    private final Set<UUID> loggedIn = ConcurrentHashMap.newKeySet();

    public boolean login(@NotNull UUID uuid) {
        return loggedIn.add(uuid);
    }

    public boolean logout(@NotNull UUID uuid) {
        return loggedIn.remove(uuid);
    }

    public boolean isLoggedIn(@NotNull UUID uuid) {
        return loggedIn.contains(uuid);
    }

    public @NotNull Set<UUID> snapshot() {
        return Collections.unmodifiableSet(loggedIn);
    }
}
