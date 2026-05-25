package de.eternal.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Unban-Antrag. {@link Status#SHORTENED} ist ein Sonder-Approve: der Bann
 * wird nicht aufgehoben, sondern nur in der Dauer reduziert. Der zugehörige
 * neue Restzeit-Wert ({@link #shortenedToSeconds()}) und die an den Spieler
 * weitergereichte Nachricht ({@link #decisionMessage()}) hängen direkt am
 * Antrag, damit das Dashboard sie zeigen kann ohne extra Lookup.
 */
public record UnbanAppeal(
        long id,
        long banId,
        @NotNull UUID applicantUuid,
        @NotNull String applicantName,
        @NotNull String text,
        @NotNull Status status,
        @NotNull Instant createdAt,
        @Nullable UUID reviewerUuid,
        @Nullable String reviewerName,
        @Nullable Instant reviewedAt,
        @Nullable String decisionReason,
        /** Spielerlesbarer Begleittext zur Entscheidung — getrennt vom
         *  internen {@link #decisionReason()}, weil der Spieler ihn auf seiner
         *  Konto-Seite und im Kick-Screen sieht. */
        @Nullable String decisionMessage,
        /** Nur gesetzt wenn {@link #status()} == SHORTENED: die neue
         *  Rest-Dauer in Sekunden (relativ zum {@link #reviewedAt()}). */
        @Nullable Long shortenedToSeconds
) {
    public enum Status {
        PENDING,
        APPROVED,
        DENIED,
        SHORTENED
    }
}
