package com.veyrion.character.util;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and formats simple durations such as {@code 90}, {@code 30m}, {@code 1d12h}. */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+)([smhd])");

    private Durations() {}

    /** Plain numbers are seconds. Units: s, m, h, d; they may be combined ("1d12h"). */
    public static OptionalLong parseSeconds(String text) {
        if (text == null) {
            return OptionalLong.empty();
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            if (value.chars().allMatch(Character::isDigit)) {
                return OptionalLong.of(Long.parseLong(value));
            }
            Matcher matcher = PART.matcher(value);
            long total = 0;
            int position = 0;
            while (matcher.find()) {
                if (matcher.start() != position) {
                    return OptionalLong.empty();
                }
                long amount = Long.parseLong(matcher.group(1));
                total += amount * switch (matcher.group(2).charAt(0)) {
                    case 's' -> 1L;
                    case 'm' -> 60L;
                    case 'h' -> 3600L;
                    default -> 86400L;
                };
                position = matcher.end();
            }
            return position == value.length() ? OptionalLong.of(total) : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    public static String format(long seconds) {
        if (seconds <= 0) {
            return "0s";
        }
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        long minutes = seconds % 3600 / 60;
        long secs = seconds % 60;
        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            builder.append(days).append("d ");
        }
        if (hours > 0) {
            builder.append(hours).append("h ");
        }
        if (minutes > 0) {
            builder.append(minutes).append("m ");
        }
        if (secs > 0 && days == 0) {
            builder.append(secs).append("s");
        }
        return builder.toString().trim();
    }
}
