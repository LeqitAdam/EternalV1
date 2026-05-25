package de.eternal.spigot;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.ChatColor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds BaseComponent[] arrays from legacy &-coloured strings, with helpers
 * for the recurring "make a staff name clickable" pattern (click = run
 * /lookup, hover = translated tooltip).
 */
public final class Components {

    private Components() {
    }

    /** Build a clickable staff-name component using the active translation. */
    public static @NotNull BaseComponent clickableStaff(@NotNull Messages messages,
                                                       @NotNull String name) {
        return clickableDisplay(messages, name, "&e" + name);
    }

    /**
     * Same as {@link #clickableStaff} but prefixes the rendered text with
     * "[Rang]" when a group name is known. Click event runs /lookup, hover
     * shows the standard staff tooltip.
     *
     * <p>Used by /history rows where we don't have a cached DisplayName for
     * offline staff — the group-prefixed fallback gives at least a rank hint.</p>
     */
    public static @NotNull BaseComponent clickableStaffWithGroup(@NotNull Messages messages,
                                                                 @NotNull String name,
                                                                 String group) {
        String legacy = (group != null && !group.isEmpty())
                ? "&8[&f" + group + "&8] &e" + name
                : "&e" + name;
        return clickableDisplay(messages, name, legacy);
    }

    /**
     * Renders {@code legacyDisplay} verbatim, but wraps it in a click event
     * that runs {@code /lookup <name>} so the rank-coloured DisplayName from
     * CloudNet-Chat keeps its formatting and stays clickable.
     */
    public static @NotNull BaseComponent clickableDisplay(@NotNull Messages messages,
                                                          @NotNull String name,
                                                          @NotNull String legacyDisplay) {
        TextComponent c = legacy(legacyDisplay);
        c.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lookup " + name));
        c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(legacyArray(messages.format("hover-show-staff", "name", name)))));
        return c;
    }

    public static @NotNull TextComponent legacy(@NotNull String legacy) {
        return new TextComponent(legacyArray(ChatColor.translateAlternateColorCodes('&', legacy)));
    }

    public static @NotNull BaseComponent[] legacyArray(@NotNull String legacy) {
        return TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', legacy));
    }

    /** Concatenate legacy fragments and inline components into one array. */
    public static @NotNull BaseComponent[] concat(@NotNull Object... parts) {
        List<BaseComponent> out = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof BaseComponent bc) {
                out.add(bc);
            } else if (part instanceof BaseComponent[] arr) {
                for (BaseComponent c : arr) out.add(c);
            } else if (part != null) {
                for (BaseComponent c : legacyArray(part.toString())) out.add(c);
            }
        }
        return out.toArray(new BaseComponent[0]);
    }
}
