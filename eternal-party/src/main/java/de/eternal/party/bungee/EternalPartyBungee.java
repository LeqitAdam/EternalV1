package de.eternal.party.bungee;

import net.md_5.bungee.api.plugin.Plugin;

/**
 * BungeeCord companion of the Eternal party feature. Pure cross-server
 * notifier: registers the {@code eternal:party} channel and a listener that
 * delivers backend-rendered messages to a player wherever they are connected.
 * No DB, no commands — all party/friend logic stays on the backends.
 */
public final class EternalPartyBungee extends Plugin {

    public static final String CHANNEL = "eternal:party";

    @Override
    public void onEnable() {
        getProxy().registerChannel(CHANNEL);
        getProxy().getPluginManager().registerListener(this, new PartyDeliverListener(this));
        getLogger().info("EternalParty (Bungee) aktiv — Cross-Server-Benachrichtigungen.");
    }
}
