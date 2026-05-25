package de.eternal.replay.internal.storage;

import de.eternal.replay.model.BlockEvent;
import de.eternal.replay.model.ChatEvent;
import de.eternal.replay.model.HitEvent;
import de.eternal.replay.model.InventorySnapshot;
import de.eternal.replay.model.ItemUseEvent;
import de.eternal.replay.model.MetaEvent;
import de.eternal.replay.model.MovementFrame;
import de.eternal.replay.model.Recordable;
import org.jetbrains.annotations.NotNull;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Binary format reader/writer for replay files. Format (all big-endian):
 *
 * <pre>
 * HEADER:
 *   magic           8 bytes  "ETERNALR"
 *   version         int      currently 1
 *   sessionStartMs  long
 *   numPlayers      int
 *   for each player:
 *     uuid most/least  2 * long
 *     name             UTF-8 string (DataOutput.writeUTF)
 *
 * RECORDS (until EOF):
 *   typeId          byte
 *   relativeMs      int
 *   playerIdx       int (signed; -1 for global events)
 *   payload         variable, depending on type
 * </pre>
 *
 * <p>Bumping the version is mandatory if the per-type payload changes.
 * Writers always emit the current version; readers tolerate any version
 * by switching on the version byte.</p>
 */
public final class ReplayCodec {

    static final byte[] MAGIC = "ETERNALR".getBytes(StandardCharsets.UTF_8);
    static final int CURRENT_VERSION = 1;

    private ReplayCodec() {
    }

    /** Player entry in the header. The {@code idx} is the implicit array index. */
    public record PlayerRef(@NotNull UUID uuid, @NotNull String name) {
    }

    /* ------------------------------ writing ------------------------------ */

    public static void writeHeader(@NotNull DataOutputStream out,
                                   long sessionStartMs,
                                   @NotNull List<PlayerRef> players) throws IOException {
        out.write(MAGIC);
        out.writeInt(CURRENT_VERSION);
        out.writeLong(sessionStartMs);
        out.writeInt(players.size());
        for (PlayerRef p : players) {
            out.writeLong(p.uuid.getMostSignificantBits());
            out.writeLong(p.uuid.getLeastSignificantBits());
            out.writeUTF(p.name);
        }
    }

    public static void writeRecord(@NotNull DataOutputStream out, int playerIdx,
                                   @NotNull Recordable record) throws IOException {
        out.writeByte(record.type().id());
        out.writeInt(record.relativeMs());
        out.writeInt(playerIdx);
        // Java 17 — keine pattern-switches. Type-switch über die enum +
        // klassisches Casting reicht (alle subtypes sind sealed).
        switch (record.type()) {
            case MOVEMENT -> {
                MovementFrame m = (MovementFrame) record;
                out.writeDouble(m.x());
                out.writeDouble(m.y());
                out.writeDouble(m.z());
                out.writeFloat(m.yaw());
                out.writeFloat(m.pitch());
                out.writeBoolean(m.onGround());
                out.writeBoolean(m.sneaking());
                out.writeBoolean(m.sprinting());
                out.writeBoolean(m.flying());
                out.writeBoolean(m.swimming());
                out.writeUTF(m.mainHandMaterial());
            }
            case HIT -> {
                HitEvent h = (HitEvent) record;
                out.writeInt(h.damagerIdx());
                out.writeInt(h.victimIdx());
                out.writeDouble(h.damage());
                out.writeUTF(h.weaponMaterial());
                out.writeDouble(h.reachDistance());
            }
            case CHAT -> out.writeUTF(((ChatEvent) record).message());
            case BLOCK_PLACE, BLOCK_BREAK -> {
                BlockEvent b = (BlockEvent) record;
                out.writeBoolean(b.placed());
                out.writeInt(b.blockX());
                out.writeInt(b.blockY());
                out.writeInt(b.blockZ());
                out.writeUTF(b.dataString());
            }
            case ITEM_USE -> {
                ItemUseEvent u = (ItemUseEvent) record;
                out.writeUTF(u.action());
                out.writeUTF(u.material());
            }
            case INVENTORY -> out.writeUTF(((InventorySnapshot) record).base64Data());
            case META -> out.writeUTF(((MetaEvent) record).kind().name());
        }
    }

    /* ------------------------------ reading ------------------------------ */

    public record DecodedHeader(int version, long sessionStartMs, @NotNull List<PlayerRef> players) {
    }

    public static @NotNull DecodedHeader readHeader(@NotNull DataInputStream in) throws IOException {
        byte[] magic = new byte[8];
        in.readFully(magic);
        for (int i = 0; i < 8; i++) {
            if (magic[i] != MAGIC[i]) throw new IOException("Not an Eternal replay (bad magic)");
        }
        int version = in.readInt();
        long sessionStart = in.readLong();
        int n = in.readInt();
        List<PlayerRef> players = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long msb = in.readLong();
            long lsb = in.readLong();
            String name = in.readUTF();
            players.add(new PlayerRef(new UUID(msb, lsb), name));
        }
        return new DecodedHeader(version, sessionStart, players);
    }

    /** One record bundle that pairs the recorded entity (raw fields) with
     *  the playerIdx + relative-ms metadata. */
    public record DecodedRecord(@NotNull Recordable.Type type, int relativeMs, int playerIdx,
                                 @NotNull Recordable payload) {
    }

    /** Reads exactly one record, or returns null at EOF. */
    public static DecodedRecord readRecord(@NotNull DataInputStream in) throws IOException {
        byte typeId;
        try { typeId = in.readByte(); }
        catch (EOFException eof) { return null; }
        Recordable.Type type = Recordable.Type.byId(typeId);
        int relMs = in.readInt();
        int playerIdx = in.readInt();
        Recordable r = switch (type) {
            case MOVEMENT -> new MovementFrame(
                    relMs, playerIdx,
                    in.readDouble(), in.readDouble(), in.readDouble(),
                    in.readFloat(), in.readFloat(),
                    in.readBoolean(), in.readBoolean(), in.readBoolean(),
                    in.readBoolean(), in.readBoolean(),
                    in.readUTF());
            case HIT -> new HitEvent(
                    relMs, in.readInt(), in.readInt(),
                    in.readDouble(), in.readUTF(), in.readDouble());
            case CHAT -> new ChatEvent(relMs, playerIdx, in.readUTF());
            case BLOCK_PLACE, BLOCK_BREAK -> {
                boolean placed = in.readBoolean();
                int bx = in.readInt(), by = in.readInt(), bz = in.readInt();
                String data = in.readUTF();
                yield new BlockEvent(relMs, playerIdx, placed, bx, by, bz, data);
            }
            case ITEM_USE -> new ItemUseEvent(relMs, playerIdx, in.readUTF(), in.readUTF());
            case INVENTORY -> new InventorySnapshot(relMs, playerIdx, in.readUTF());
            case META -> new MetaEvent(relMs, playerIdx, MetaEvent.Kind.valueOf(in.readUTF()));
        };
        return new DecodedRecord(type, relMs, playerIdx, r);
    }
}
