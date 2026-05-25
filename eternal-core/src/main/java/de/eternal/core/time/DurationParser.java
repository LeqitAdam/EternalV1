package de.eternal.core.time;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Parses friendly duration strings like "30d", "12h", "1w", "permanent".
 * Returns -1 for permanent.
 */
public final class DurationParser {

    private DurationParser() {
    }

    public static long parseToSeconds(@NotNull String input) {
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("permanent") || s.equals("perm") || s.equals("forever") || s.equals("-1")) {
            return -1L;
        }

        long total = 0L;
        int i = 0;
        while (i < s.length()) {
            int numStart = i;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (numStart == i) {
                throw new IllegalArgumentException("Invalid duration: " + input);
            }
            long value = Long.parseLong(s.substring(numStart, i));

            if (i >= s.length()) {
                throw new IllegalArgumentException("Duration missing unit: " + input);
            }
            char unit = s.charAt(i++);
            long multiplier = switch (unit) {
                case 's' -> 1L;
                case 'm' -> 60L;
                case 'h' -> 3600L;
                case 'd' -> 86_400L;
                case 'w' -> 604_800L;
                case 'y' -> 31_536_000L;
                default -> throw new IllegalArgumentException("Unknown unit '" + unit + "' in " + input);
            };
            total += value * multiplier;
        }
        return total;
    }

    public static @NotNull String formatRemaining(long seconds) {
        if (seconds < 0) return "permanent";
        if (seconds < 60) return seconds + "s";

        long days = seconds / 86_400L;
        long hours = (seconds % 86_400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0 && days == 0) sb.append(minutes).append("m");
        return sb.toString().trim();
    }
}
