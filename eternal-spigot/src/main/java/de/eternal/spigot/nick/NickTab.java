package de.eternal.spigot.nick;

import de.eternal.core.text.MessageBank;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Owns the nicked player's tab/name-tag prefix via a Bukkit scoreboard team on
 * the main scoreboard.
 *
 * <p>Why a team and not just {@code setPlayerListName}: once the disguise
 * rewrites the player's {@code GameProfile} name, the client knows the entity
 * by the FAKE name. Any external tab/chat plugin keys its own team off the
 * player's REAL name, so its entry no longer matches the visible entry — the
 * tab falls back to no prefix (the "shown as Spieler" bug). The client matches
 * scoreboard teams by the tab-list entry string, which for a player is the
 * profile name, so we register a team whose single entry is the fake name and
 * give it the disguised rank's prefix. The real-name team the external plugin
 * manages is left untouched, so the real prefix returns automatically on
 * unnick.</p>
 *
 * <p>This never reads or writes CloudNet groups — purely cosmetic, so a nicked
 * player keeps their own permissions (stealth).</p>
 */
final class NickTab {

    private NickTab() {
    }

    /** Stable, ≤16-char team name derived from the player UUID. */
    private static @NotNull String teamName(@NotNull UUID uuid) {
        String n = "nk_" + Long.toHexString(uuid.getMostSignificantBits());
        return n.length() > 16 ? n.substring(0, 16) : n;
    }

    /** Show {@code coloredPrefix}/{@code coloredSuffix} around the nicked
     *  player's {@code fakeName} in tab + over their head. Both are raw
     *  {@code &}-coded strings (either may be empty). The player's own list
     *  name should be set to the plain fake name so the team — not the list
     *  name — supplies the prefix, avoiding a doubled prefix. */
    static void apply(@NotNull Player player, @NotNull String fakeName,
                      @NotNull String coloredPrefix, @NotNull String coloredSuffix) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String name = teamName(player.getUniqueId());
        Team team = sb.getTeam(name);
        if (team == null) team = sb.registerNewTeam(name);
        // Drop any stale entry (e.g. a previous nick with a different fake name).
        for (String entry : new ArrayList<>(team.getEntries())) team.removeEntry(entry);
        team.setPrefix(trim64(MessageBank.colorize(coloredPrefix)));
        team.setSuffix(trim64(MessageBank.colorize(coloredSuffix)));
        // Colour the entry name (over-head + tab) in the rank colour so the fake
        // name isn't plain white when the prefix is e.g. "&aPremium ".
        ChatColor color = lastColor(coloredPrefix);
        if (color != null) {
            try { team.setColor(color); } catch (Throwable ignored) { /* pre-1.13 */ }
        }
        team.addEntry(fakeName); // client matches by the visible (fake) profile name
    }

    /** The trailing &amp;-colour code of a prefix as a legacy code string
     *  (e.g. "&a"), or "" when none — used to colour the tab list name too. */
    static @NotNull String nameColorCode(@NotNull String coloredPrefix) {
        ChatColor c = lastColor(coloredPrefix);
        return c == null ? "" : "&" + c.getChar();
    }

    /** Last COLOUR (not format) code in {@code &}/{@code §}-coded text. */
    private static ChatColor lastColor(@NotNull String s) {
        ChatColor found = null;
        for (int i = 0; i + 1 < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '&' && ch != '§') continue;
            char code = Character.toLowerCase(s.charAt(i + 1));
            if ("0123456789abcdef".indexOf(code) >= 0) found = ChatColor.getByChar(code);
        }
        return found;
    }

    /** Team prefix/suffix cap at 64 chars on 1.13+; trim defensively. */
    private static @NotNull String trim64(@NotNull String s) {
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    /** Tear the disguise team down again. No-op when it was never created. */
    static void clear(@NotNull UUID uuid) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = sb.getTeam(teamName(uuid));
        if (team != null) {
            try {
                team.unregister();
            } catch (IllegalStateException ignored) {
                // already unregistered
            }
        }
    }
}
