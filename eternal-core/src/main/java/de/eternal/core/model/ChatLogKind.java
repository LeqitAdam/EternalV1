package de.eternal.core.model;

/**
 * What kind of line a {@link ChatLogEntry} represents.
 *
 * <p>Sensitive commands (login/register/...) are NOT a separate kind — they
 * live in a separate table and are stored there with kind {@link #COMMAND}.</p>
 */
public enum ChatLogKind {
    /** Public chat message. */
    CHAT,
    /** A command (everything that isn't sensitive). */
    COMMAND,
    /** A private message (/msg) — carries target uuid/name. */
    MSG
}
