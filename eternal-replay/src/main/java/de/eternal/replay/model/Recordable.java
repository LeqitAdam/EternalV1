package de.eternal.replay.model;

/**
 * Every record type that the recorder can emit implements this interface.
 * The {@link #type()} byte is the discriminator in the binary file so we
 * can switch on it during playback. {@link #relativeMs()} is the offset
 * from the replay's session start in milliseconds.
 *
 * <p>Record subtypes are intentionally small POJOs / records: the
 * serialization layer ({@code ReplayCodec}) writes them with explicit
 * field-by-field DataOutput calls, so changing the record's shape requires
 * bumping the codec version.</p>
 */
public sealed interface Recordable
        permits MovementFrame, HitEvent, ChatEvent, BlockEvent, ItemUseEvent, InventorySnapshot, MetaEvent {

    /** Type discriminator written as a single byte in the binary stream. */
    Type type();

    /** Milliseconds since the replay's session start. */
    int relativeMs();

    /**
     * Single-byte discriminator at the start of every record. Order is fixed
     * — never reorder values, only ever append, because old replay files
     * would break otherwise.
     */
    enum Type {
        MOVEMENT,     // 0 — position/rotation/state every 4 ticks
        HIT,          // 1 — damage dealt between two entities
        CHAT,         // 2 — chat message
        BLOCK_PLACE,  // 3 — block placed
        BLOCK_BREAK,  // 4 — block broken
        ITEM_USE,     // 5 — right-click interact, eat, bow draw
        INVENTORY,    // 6 — full inventory snapshot (every 5s)
        META;         // 7 — control event (player joined/left mid-recording)

        public byte id() { return (byte) ordinal(); }

        public static Type byId(byte id) {
            Type[] all = values();
            int idx = id & 0xff;
            if (idx < 0 || idx >= all.length) throw new IllegalArgumentException("Unknown type id: " + id);
            return all[idx];
        }
    }
}
