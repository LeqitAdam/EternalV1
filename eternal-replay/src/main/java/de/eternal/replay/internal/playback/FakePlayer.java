package de.eternal.replay.internal.playback;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake-player NPC für den Replay-Viewer. Im Gegensatz zur ArmorStand-
 * Variante hat er das echte Minecraft-Spieler-Modell inkl. Walking-
 * Animation, Head-Rotation und korrektem Skin.
 *
 * <p>Wird komplett über ProtocolLib-Pakete an einen einzigen Viewer
 * geschickt — keine Server-seitige Entity. Das hat zwei Vorteile:
 * (1) andere Spieler sehen den Ghost nicht, (2) das Echtweltgeschehen
 * wird nicht beeinträchtigt.</p>
 *
 * <p>Skin wird vom echten {@code OfflinePlayer.getPlayerProfile()} gezogen
 * — wenn Paper das nicht hergibt (z.B. niemand mit dieser UUID auf dem
 * Server bekannt), bleibt der Default-Steve-Skin.</p>
 */
public final class FakePlayer implements GhostAvatar {

    /** Entity-IDs > 1_000_000 — weit weg von realen Spielern, kein Konflikt. */
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(1_000_000);

    private final Player viewer;
    private final UUID profileUuid;
    /** Display-UUID — RANDOM, damit das gleichzeitige Sehen des echten
     *  Spielers (z.B. wenn er noch online ist) kein Konflikt gibt. */
    private final UUID displayUuid;
    private final String displayName;
    private final int entityId;
    /** Pre-captured skin from the replay header (since v2). When empty
     *  we fall back to {@code OfflinePlayer.getPlayerProfile()} for
     *  a live lookup — that matches the original behaviour. */
    private final String preCapturedSkinValue;
    private final String preCapturedSkinSignature;
    private boolean spawned = false;

    public FakePlayer(@NotNull Player viewer, @NotNull UUID profileUuid, @NotNull String displayName) {
        this(viewer, profileUuid, displayName, "", "");
    }

    public FakePlayer(@NotNull Player viewer, @NotNull UUID profileUuid, @NotNull String displayName,
                      @NotNull String skinValue, @NotNull String skinSignature) {
        this.viewer = viewer;
        this.profileUuid = profileUuid;
        this.displayUuid = UUID.randomUUID();
        this.displayName = displayName;
        this.entityId = NEXT_ENTITY_ID.incrementAndGet();
        this.preCapturedSkinValue = skinValue;
        this.preCapturedSkinSignature = skinSignature;
    }

    /** Sendet die Pakete um den Fake-Player erstmals erscheinen zu lassen. */
    public void spawn(@NotNull Location loc) {
        if (spawned) return;
        spawned = true;
        WrappedGameProfile profile = buildProfile();

        // 1) PLAYER_INFO add — Tablist + Skin-Properties einspielen.
        // Die API änderte sich zwischen 1.19.2 (single action) und 1.19.3+
        // (set of actions). Wir versuchen den modernen Pfad zuerst und
        // fallen auf den alten zurück, falls die Methode nicht existiert.
        PacketContainer info = ProtocolLibrary.getProtocolManager()
                .createPacket(PacketType.Play.Server.PLAYER_INFO);
        PlayerInfoData data = new PlayerInfoData(
                profile, 0, EnumWrappers.NativeGameMode.SURVIVAL,
                WrappedChatComponent.fromText(displayName));
        if (!tryWritePlayerInfoModern(info, data, /*add=*/ true)) {
            writePlayerInfoLegacy(info, data, /*add=*/ true);
        }
        send(info);

        // 2) NAMED_ENTITY_SPAWN — eigentliches Entity im 3D-Raum
        PacketContainer spawn = ProtocolLibrary.getProtocolManager()
                .createPacket(PacketType.Play.Server.NAMED_ENTITY_SPAWN);
        spawn.getIntegers().write(0, entityId);
        spawn.getUUIDs().write(0, displayUuid);
        spawn.getDoubles().write(0, loc.getX()).write(1, loc.getY()).write(2, loc.getZ());
        spawn.getBytes().write(0, toAngle(loc.getYaw())).write(1, toAngle(loc.getPitch()));
        send(spawn);

        // 3) Sofort den HEAD_ROTATION nachschieben — der Yaw aus dem Spawn-
        // Paket setzt nur den Body, der Kopf braucht eine eigene Drehung.
        sendHeadRotation(loc.getYaw());

        // 4) Nach kurzer Verzögerung wieder aus der Tablist entfernen,
        // sonst sieht das Mod-UI N zusätzliche Einträge.
        org.bukkit.plugin.Plugin replay = Bukkit.getPluginManager().getPlugin("EternalReplay");
        if (replay != null) {
            Bukkit.getScheduler().runTaskLater(replay, this::hideFromTabList, 20L);
        }
    }

    /** Bewegt den Ghost zu einer neuen Location. */
    public void teleport(@NotNull Location loc) {
        if (!spawned) { spawn(loc); return; }
        PacketContainer tp = ProtocolLibrary.getProtocolManager()
                .createPacket(PacketType.Play.Server.ENTITY_TELEPORT);
        tp.getIntegers().write(0, entityId);
        tp.getDoubles().write(0, loc.getX()).write(1, loc.getY()).write(2, loc.getZ());
        tp.getBytes().write(0, toAngle(loc.getYaw())).write(1, toAngle(loc.getPitch()));
        tp.getBooleans().write(0, true); // onGround
        send(tp);
        sendHeadRotation(loc.getYaw());
    }

    /** Setzt das Item in der Haupthand. */
    public void setMainHand(@Nullable ItemStack item) {
        sendEquipment(EnumWrappers.ItemSlot.MAINHAND, item);
    }

    public void setOffHand(@Nullable ItemStack item)  { sendEquipment(EnumWrappers.ItemSlot.OFFHAND, item); }
    public void setHelmet(@Nullable ItemStack item)   { sendEquipment(EnumWrappers.ItemSlot.HEAD, item); }
    public void setChest(@Nullable ItemStack item)    { sendEquipment(EnumWrappers.ItemSlot.CHEST, item); }
    public void setLeggings(@Nullable ItemStack item) { sendEquipment(EnumWrappers.ItemSlot.LEGS, item); }
    public void setBoots(@Nullable ItemStack item)    { sendEquipment(EnumWrappers.ItemSlot.FEET, item); }

    /** Räumt den Ghost beim Viewer wieder weg. */
    public void remove() {
        if (!spawned) return;
        spawned = false;
        PacketContainer destroy = ProtocolLibrary.getProtocolManager()
                .createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        destroy.getIntLists().write(0, java.util.List.of(entityId));
        send(destroy);
        hideFromTabList();
    }

    /* ----------------------- internals ----------------------- */

    /** Wandelt einen Yaw/Pitch (-180..180) in das 256-Schritt-Format. */
    private static byte toAngle(float deg) {
        return (byte) Math.round(deg * 256.0F / 360.0F);
    }

    private void sendHeadRotation(float yaw) {
        PacketContainer head = ProtocolLibrary.getProtocolManager()
                .createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        head.getIntegers().write(0, entityId);
        head.getBytes().write(0, toAngle(yaw));
        send(head);
    }

    private void sendEquipment(@NotNull EnumWrappers.ItemSlot slot, @Nullable ItemStack item) {
        if (!spawned) return;
        PacketContainer eq = ProtocolLibrary.getProtocolManager()
                .createPacket(PacketType.Play.Server.ENTITY_EQUIPMENT);
        eq.getIntegers().write(0, entityId);
        eq.getSlotStackPairLists().write(0, java.util.List.of(
                new com.comphenix.protocol.wrappers.Pair<>(slot, item == null ? new ItemStack(org.bukkit.Material.AIR) : item)));
        send(eq);
    }

    private void hideFromTabList() {
        PacketContainer info = ProtocolLibrary.getProtocolManager()
                .createPacket(PacketType.Play.Server.PLAYER_INFO);
        PlayerInfoData data = new PlayerInfoData(
                buildProfile(),
                0,
                EnumWrappers.NativeGameMode.SURVIVAL,
                WrappedChatComponent.fromText(displayName));
        if (!tryWritePlayerInfoModern(info, data, /*add=*/ false)) {
            writePlayerInfoLegacy(info, data, /*add=*/ false);
        }
        send(info);
    }

    /** Baut das WrappedGameProfile inkl. Skin-Property (falls verfügbar).
     *  <p>Skin-Quelle in dieser Reihenfolge:</p>
     *  <ol>
     *      <li>Pre-captured aus dem Replay-File (header v2). Das ist der
     *          "Wie ein Video"-Pfad — selbst wenn der echte Spieler
     *          inzwischen entbannt + skin geändert hat, sehen wir den
     *          historischen Skin von Aufnahmezeit.</li>
     *      <li>Live-Lookup über {@code OfflinePlayer.getPlayerProfile()}.
     *          Fallback für v1-Replays ohne Skin-Daten und für Setups
     *          ohne Paper-API.</li>
     *      <li>Default Steve (kein Property gesetzt) wenn nichts greift.</li>
     *  </ol> */
    private @NotNull WrappedGameProfile buildProfile() {
        WrappedGameProfile profile = new WrappedGameProfile(displayUuid, displayName);
        // Path 1 — header skin from v2 replay file. Skip when empty so
        // the live lookup gets a chance.
        if (!preCapturedSkinValue.isEmpty()) {
            profile.getProperties().put("textures",
                    new WrappedSignedProperty("textures",
                            preCapturedSkinValue, preCapturedSkinSignature));
            return profile;
        }
        // Path 2 — live profile via Paper reflection (legacy behaviour
        // for v1 replays). Errors silently fall through to Steve.
        try {
            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(profileUuid);
            var method = op.getClass().getMethod("getPlayerProfile");
            Object pp = method.invoke(op);
            var propsMethod = pp.getClass().getMethod("getProperties");
            @SuppressWarnings("unchecked")
            java.util.Set<Object> props = (java.util.Set<Object>) propsMethod.invoke(pp);
            for (Object prop : props) {
                String pName = (String) prop.getClass().getMethod("getName").invoke(prop);
                if (!"textures".equals(pName)) continue;
                String pValue = (String) prop.getClass().getMethod("getValue").invoke(prop);
                Object pSig = prop.getClass().getMethod("getSignature").invoke(prop);
                profile.getProperties().put("textures",
                        new WrappedSignedProperty("textures", pValue,
                                pSig == null ? "" : pSig.toString()));
            }
        } catch (Throwable ignored) { /* skin lookup failed — Steve is fine */ }
        return profile;
    }

    private void send(@NotNull PacketContainer packet) {
        try {
            ProtocolLibrary.getProtocolManager().sendServerPacket(viewer, packet);
        } catch (Exception ex) {
            // Network errors during heavy traffic shouldn't crash playback —
            // log via ProtocolLib's own facility would be ideal but Bukkit
            // logger works fine.
            Bukkit.getLogger().warning("FakePlayer packet send failed: " + ex.getMessage());
        }
    }

    /**
     * 1.19.3+ Pfad: PLAYER_INFO_UPDATE-Packet hat eine {@code EnumSet<Action>}
     * + Liste von Einträgen. ProtocolLib exponiert das über
     * {@code getPlayerInfoActions()} (Plural) — wenn der unterliegende Packet-
     * Struct das nicht hat (z.B. 1.19.2), wirft das {@link Throwable},
     * den wir abfangen und mit {@code false} signalisieren.
     */
    private boolean tryWritePlayerInfoModern(@NotNull PacketContainer info,
                                             @NotNull PlayerInfoData data,
                                             boolean add) {
        try {
            EnumSet<EnumWrappers.PlayerInfoAction> actions = add
                    ? EnumSet.of(EnumWrappers.PlayerInfoAction.ADD_PLAYER)
                    : EnumSet.of(EnumWrappers.PlayerInfoAction.REMOVE_PLAYER);
            info.getPlayerInfoActions().write(0, actions);
            // Die Daten-Liste sitzt auf 1.19.3+ auf Index 1 (Index 0 ist die
            // UUID-Liste für Remove). Falls das doch auf 0 liegt — z.B. ältere
            // ProtocolLib-Builds — fallen wir intern darauf zurück.
            try {
                info.getPlayerInfoDataLists().write(1, java.util.List.of(data));
            } catch (Throwable inner) {
                info.getPlayerInfoDataLists().write(0, java.util.List.of(data));
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 1.19.2 Pfad: PLAYER_INFO-Packet hat ein einzelnes {@code Action}-Enum
     * (Singular) + Liste von Einträgen auf Index 0. Auf 1.19.2 + Spigot 1.19
     * ist das der primäre Weg.
     */
    private void writePlayerInfoLegacy(@NotNull PacketContainer info,
                                       @NotNull PlayerInfoData data,
                                       boolean add) {
        info.getPlayerInfoAction().write(0,
                add ? EnumWrappers.PlayerInfoAction.ADD_PLAYER
                    : EnumWrappers.PlayerInfoAction.REMOVE_PLAYER);
        info.getPlayerInfoDataLists().write(0, java.util.List.of(data));
    }
}
