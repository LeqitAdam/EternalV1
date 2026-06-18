package de.eternal.party.spigot.net;

/**
 * Plugin-message channel shared by the Spigot backend and the BungeeCord proxy
 * companion. Follows the existing {@code eternal:<feature>} convention.
 *
 * <p>Single subcommand {@code "deliver"} — the backend renders a finished
 * message addressed to a UUID and the proxy forwards it to that player wherever
 * they are. Wire (Guava {@code ByteArrayDataOutput}):</p>
 * <pre>
 *   writeUTF("deliver")
 *   writeUTF(targetUuid)
 *   writeUTF(legacyText)     // already §-coloured, may contain line breaks
 *   writeUTF(clickCommand)   // "" = no click
 * </pre>
 */
public final class PartyChannel {

    public static final String CHANNEL = "eternal:party";
    public static final String SUB_DELIVER = "deliver";

    private PartyChannel() {
    }
}
