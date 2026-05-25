package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted punishment record (ban or mute).
 * expiresAt == null means permanent.
 */
public record PunishmentEntry(
        long id,
        @NotNull PunishmentType type,
        @NotNull UUID targetUuid,
        @NotNull String targetName,
        @Nullable UUID issuerUuid,
        @NotNull String issuerName,
        @NotNull String reasonId,
        @NotNull String reasonLabel,
        @NotNull String publicMessage,
        @NotNull Instant issuedAt,
        @Nullable Instant expiresAt,
        boolean active,
        @Nullable UUID pardonIssuerUuid,
        @Nullable String pardonIssuerName,
        @Nullable String pardonReason,
        @Nullable Instant pardonedAt,
        @Nullable Instant modifiedAt,
        @Nullable UUID modifiedByUuid,
        @Nullable String modifiedByName
) {

    public boolean isPermanent() {
        return expiresAt == null;
    }

    public boolean isCurrentlyEffective(@NotNull Instant now) {
        if (!active) return false;
        return expiresAt == null || now.isBefore(expiresAt);
    }
}
