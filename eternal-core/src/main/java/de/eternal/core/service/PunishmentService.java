package de.eternal.core.service;

import de.eternal.core.model.PunishmentEntry;
import de.eternal.core.model.PunishmentReason;
import de.eternal.core.model.PunishmentType;
import de.eternal.core.storage.EternalStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PunishmentService {

    private final EternalStorage storage;

    public PunishmentService(@NotNull EternalStorage storage) {
        this.storage = Objects.requireNonNull(storage);
    }

    public @NotNull PunishmentEntry issue(
            @NotNull UUID targetUuid,
            @NotNull String targetName,
            @Nullable UUID issuerUuid,
            @NotNull String issuerName,
            @NotNull PunishmentReason reason
    ) {
        Instant now = Instant.now();
        // Escalation: count prior, completed offenses of the same reason and
        // pick the matching duration from the ladder. Without a ladder this
        // falls through to the reason's default duration.
        int priorOffenses = storage.countPriorOffenses(targetUuid, String.valueOf(reason.id()), reason.type());
        long effectiveDuration = reason.resolveDurationFor(priorOffenses);
        Instant expires = effectiveDuration < 0 ? null : now.plusSeconds(effectiveDuration);

        PunishmentEntry draft = new PunishmentEntry(
                -1L,
                reason.type(),
                targetUuid,
                targetName,
                issuerUuid,
                issuerName,
                String.valueOf(reason.id()),
                reason.label(),
                reason.label(), // publicMessage column kept for forward-compat; label is fine for now
                now,
                expires,
                true,
                null, null, null, null,
                null, null, null
        );

        long id = storage.insertPunishment(draft);
        return new PunishmentEntry(
                id, draft.type(), draft.targetUuid(), draft.targetName(),
                draft.issuerUuid(), draft.issuerName(), draft.reasonId(), draft.reasonLabel(),
                draft.publicMessage(), draft.issuedAt(), draft.expiresAt(), true,
                null, null, null, null,
                null, null, null
        );
    }

    public @NotNull Optional<PunishmentEntry> activeBan(@NotNull UUID target) {
        return storage.findActivePunishment(target, PunishmentType.BAN);
    }

    public @NotNull Optional<PunishmentEntry> activeMute(@NotNull UUID target) {
        return storage.findActivePunishment(target, PunishmentType.MUTE);
    }

    public boolean pardon(long id, @Nullable UUID issuerUuid, @NotNull String issuerName, @NotNull String reason) {
        return storage.pardonPunishment(id, issuerUuid, issuerName, reason);
    }

    public boolean pardonActive(@NotNull UUID target, @NotNull PunishmentType type,
                                @Nullable UUID issuerUuid, @NotNull String issuerName,
                                @NotNull String reason) {
        return storage.findActivePunishment(target, type)
                .map(p -> storage.pardonPunishment(p.id(), issuerUuid, issuerName, reason))
                .orElse(false);
    }

    public @NotNull List<PunishmentEntry> history(@NotNull UUID target, @Nullable PunishmentType type) {
        return storage.findPunishmentHistory(target, type);
    }
}
