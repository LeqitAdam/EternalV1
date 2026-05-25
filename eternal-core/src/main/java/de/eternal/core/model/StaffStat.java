package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public record StaffStat(
        @NotNull UUID staffUuid,
        @NotNull String staffName,
        long banCount,
        long muteCount,
        long reportsHandled
) {
}
