package de.eternal.replay.api;

/**
 * What a replay is FOR. Used as a filter key so the right plugin can find
 * its own replays without scanning everything. Open enum — add new kinds
 * as needed; old replays just get an unknown kind on read.
 */
public enum ReplayKind {
    /** Triggered by Eternal's report system. */
    REPORT,
    /** Triggered by a finished game session (BedWars, SkyWars, ...). */
    GAME,
    /** Triggered manually by an admin via /replay capture. */
    MANUAL
}
