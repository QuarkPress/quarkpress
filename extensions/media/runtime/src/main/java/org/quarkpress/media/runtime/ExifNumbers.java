package org.quarkpress.media.runtime;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import org.quarkpress.core.runtime.PhpValues;

/**
 * The number forms of WordPress's image metadata: EXIF fractions as numbers, EXIF and IPTC dates as Unix seconds, and
 * numbers as the text PHP writes for them. Inputs are short metadata values; longer texts are treated as no number.
 */
public final class ExifNumbers {

    /** Longest text read as a fraction or a date; anything longer is not one. */
    static final int MAX_TEXT = 128;

    private ExifNumbers() {}

    /**
     * An EXIF fraction as a number: a text with exactly one {@code /} whose two sides are numeric and whose denominator
     * is not zero gives their quotient (a {@link Long} when both sides are integers that divide exactly, as
     * {@code 50/1}, else a {@link Double}); a numeric text without {@code /} gives that number as a {@link Double}
     * ({@code "5"} is 5.0); anything else gives {@code 0L}.
     */
    public static Object frac2dec(String text) {
        if (text == null || text.length() > MAX_TEXT) {
            return 0L;
        }
        int slash = text.indexOf('/');
        if (slash < 0) {
            Object number = numeric(text);
            return number == null ? 0L : (Object) ((Number) number).doubleValue();
        }
        // a second "/" leaves the denominator non-numeric
        Object numerator = numeric(text.substring(0, slash));
        Object denominator = numeric(text.substring(slash + 1));
        if (numerator == null || denominator == null || ((Number) denominator).doubleValue() == 0) {
            return 0L;
        }
        if (numerator instanceof Long n && denominator instanceof Long d) {
            if (n % d == 0 && !(n == Long.MIN_VALUE && d == -1)) {
                return n / d;
            }
            return (double) n / (double) d;
        }
        return ((Number) numerator).doubleValue() / ((Number) denominator).doubleValue();
    }

    /**
     * An EXIF date-time ({@code 2023:10:15 14:30:00}) as Unix seconds, the time taken as UTC; a date alone is
     * midnight. As PHP's date parser does, a month or day 0 or a day past the month's end rolls into the neighbouring
     * month, hour 24 and second 60 into the next day or minute. Empty for any other form.
     */
    public static OptionalLong date2ts(String text) {
        if (text == null || text.length() > MAX_TEXT) {
            return OptionalLong.empty();
        }
        int space = text.indexOf(' ');
        String date = space < 0 ? text : text.substring(0, space);
        String time = space < 0 ? "" : text.substring(space + 1);
        int[] ymd = fields(date, ':', 3, 3, 4);
        int[] hms = time.isEmpty() ? new int[3] : fields(time, ':', 2, 3, 0);
        if (ymd == null || hms == null) {
            return OptionalLong.empty();
        }
        return dateTime(ymd[0], ymd[1], ymd[2], hms[0], hms[1], hms[2], 0);
    }

    /**
     * An IPTC Date Created ({@code 20231015}) and Time Created ({@code 143000}, optionally with a zone {@code +0200},
     * {@code -0500} or {@code Z}) as Unix seconds, UTC when no zone is given. Empty for any other form.
     */
    static OptionalLong iptcDateTime(String date, String time) {
        if (date.length() != 8 || !digits(date, 0, 8)) {
            return OptionalLong.empty();
        }
        int offsetSeconds = 0;
        String clock = time;
        if (time.length() == 7 && time.charAt(6) == 'Z') {
            clock = time.substring(0, 6);
        } else if (time.length() == 11 && (time.charAt(6) == '+' || time.charAt(6) == '-') && digits(time, 7, 11)) {
            int hours = Integer.parseInt(time, 7, 9, 10);
            int minutes = Integer.parseInt(time, 9, 11, 10);
            if (hours > 23 || minutes > 59) {
                return OptionalLong.empty();
            }
            offsetSeconds = (time.charAt(6) == '+' ? 1 : -1) * (hours * 3600 + minutes * 60);
            clock = time.substring(0, 6);
        }
        if (clock.length() != 6 || !digits(clock, 0, 6)) {
            return OptionalLong.empty();
        }
        return dateTime(
                Integer.parseInt(date, 0, 4, 10),
                Integer.parseInt(date, 4, 6, 10),
                Integer.parseInt(date, 6, 8, 10),
                Integer.parseInt(clock, 0, 2, 10),
                Integer.parseInt(clock, 2, 4, 10),
                Integer.parseInt(clock, 4, 6, 10),
                offsetSeconds);
    }

    /** A number as PHP's string conversion writes it: integers in full, floats with 14 significant digits. */
    public static String text(Object number) {
        if (number instanceof Double d) {
            return PhpValues.floatString(d);
        }
        if (number instanceof Number n) {
            return Long.toString(n.longValue());
        }
        throw new IllegalArgumentException("not a number: " + number);
    }

    /**
     * PHP's numeric-string test and value: optional surrounding whitespace, an optional sign, digits with an optional
     * fraction (at least one digit) and an optional exponent. A {@link Long} for an integer that fits, else a
     * {@link Double}; null when the text is not numeric.
     */
    static Object numeric(String text) {
        int length = text.length();
        int start = 0;
        while (start < length && PhpWhitespace.is(text.charAt(start))) {
            start++;
        }
        int end = length;
        while (end > start && PhpWhitespace.is(text.charAt(end - 1))) {
            end--;
        }
        int i = start;
        if (i < end && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
            i++;
        }
        int intDigits = 0;
        while (i < end && isDigit(text.charAt(i))) {
            i++;
            intDigits++;
        }
        boolean integer = true;
        int fractionDigits = 0;
        if (i < end && text.charAt(i) == '.') {
            integer = false;
            i++;
            while (i < end && isDigit(text.charAt(i))) {
                i++;
                fractionDigits++;
            }
        }
        if (intDigits + fractionDigits == 0) {
            return null;
        }
        if (i < end && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            integer = false;
            i++;
            if (i < end && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
                i++;
            }
            int exponentDigits = 0;
            while (i < end && isDigit(text.charAt(i))) {
                i++;
                exponentDigits++;
            }
            if (exponentDigits == 0) {
                return null;
            }
        }
        if (i != end) {
            return null;
        }
        String number = text.substring(start, end);
        if (integer) {
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException overflow) {
                return Double.parseDouble(number);
            }
        }
        return Double.parseDouble(number);
    }

    /** Whether {@code text} is numeric as PHP's {@code is_numeric} decides. */
    static boolean isNumeric(String text) {
        return text.length() <= MAX_TEXT && numeric(text) != null;
    }

    /**
     * The numbers of {@code text} split at {@code separator}: {@code min} to {@code max} fields of one or two digits,
     * the first of {@code firstWidth} digits when it is not 0; null for any other form.
     */
    private static int[] fields(String text, char separator, int min, int max, int firstWidth) {
        int[] values = new int[max];
        int count = 0;
        int from = 0;
        while (true) {
            int to = text.indexOf(separator, from);
            int end = to < 0 ? text.length() : to;
            int width = end - from;
            boolean widthOk = count == 0 && firstWidth > 0 ? width == firstWidth : width >= 1 && width <= 2;
            if (count == max || !widthOk || !digits(text, from, end)) {
                return null;
            }
            values[count++] = Integer.parseInt(text, from, end, 10);
            if (to < 0) {
                break;
            }
            from = to + 1;
        }
        if (count < min) {
            return null;
        }
        int[] result = new int[max];
        System.arraycopy(values, 0, result, 0, count);
        return result;
    }

    private static OptionalLong dateTime(int year, int month, int day, int hour, int minute, int second, int offset) {
        if (month > 12 || day > 31 || hour > 24 || minute > 59 || second > 60) {
            return OptionalLong.empty();
        }
        long days = LocalDate.of(year, 1, 1)
                .plusMonths(month - 1L)
                .plusDays(day - 1L)
                .atStartOfDay()
                .toEpochSecond(ZoneOffset.UTC);
        return OptionalLong.of(days + hour * 3600L + minute * 60L + second - offset);
    }

    private static boolean digits(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** PHP's whitespace for numeric strings: space, tab, line feed, carriage return, vertical tab, form feed. */
    private static final class PhpWhitespace {
        static boolean is(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\f';
        }
    }
}
