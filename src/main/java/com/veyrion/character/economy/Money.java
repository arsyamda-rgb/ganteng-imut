package com.veyrion.character.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Pattern;

/**
 * Parsing and formatting of Veyra amounts. Pure Java. Amounts are plain decimal numbers with at most two
 * decimals; exponent notation, signs, thousands separators, NaN and Infinity are all rejected.
 */
public final class Money {

    /** Largest amount a command or quest may use. Keeps every double operation far inside exact range. */
    public static final double MAX = 1_000_000_000_000.0;

    private static final Pattern AMOUNT = Pattern.compile("\\d{1,13}(\\.\\d{1,2})?");

    private Money() {
    }

    /** @return the amount (zero allowed), or empty if the text is not a valid amount or exceeds {@link #MAX} */
    public static OptionalDouble parse(String input) {
        if (input == null || !AMOUNT.matcher(input).matches()) {
            return OptionalDouble.empty();
        }
        double value = new BigDecimal(input).doubleValue();
        return value > MAX ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    /** Rounds to two decimals (half up). Non-finite input becomes 0. */
    public static double round(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** Thousands separators, up to two decimals, no currency: 2500 -> "2,500", 2500.5 -> "2,500.5". */
    public static String format(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }

    /** Plain number without separators, for machine use: 2500 -> "2500", 2500.5 -> "2500.5". */
    public static String plain(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
