package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Light projection of "one of my friends" — just the other side's uuid and
 * the name we last knew them by. Online state is resolved at the call site
 * (Bukkit/Bungee player lookup), not stored here.
 */
public record Friend(@NotNull UUID uuid, @NotNull String name) {
}
