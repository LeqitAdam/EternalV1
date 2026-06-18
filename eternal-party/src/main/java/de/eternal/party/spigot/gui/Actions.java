package de.eternal.party.spigot.gui;

/** PDC action tags carried by menu buttons; read by the click listener. */
public final class Actions {
    public static final String CREATE = "create";
    public static final String FRIENDS = "friends";
    public static final String MEMBERS = "members";
    public static final String SETTINGS = "settings";
    public static final String LEAVE = "leave";
    public static final String DISBAND = "disband";
    public static final String BACK = "back";
    public static final String TOGGLE_FRIEND_REQUESTS = "toggle_friend_requests";
    public static final String TOGGLE_PARTY_INVITES = "toggle_party_invites";
    /** A friend head: left-click = invite, right-click = remove. Target UUID
     *  is on the item's target PDC key. */
    public static final String FRIEND_ENTRY = "friend_entry";

    private Actions() {
    }
}
