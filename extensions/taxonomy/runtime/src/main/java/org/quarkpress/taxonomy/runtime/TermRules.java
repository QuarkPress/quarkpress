package org.quarkpress.taxonomy.runtime;

import java.math.BigInteger;
import java.util.Optional;
import java.util.OptionalLong;
import org.quarkpress.core.TermId;
import org.quarkpress.taxonomy.TermException;

/** Field rules of WordPress's term save that need no database. */
public final class TermRules {

    /** The characters the name column holds (WordPress's varchar(200)). */
    public static final int MAX_NAME = 200;

    /** The characters the slug column holds. */
    public static final int MAX_SLUG = 200;

    private TermRules() {}

    /** PHP's {@code empty()} for text: "" and "0". */
    public static boolean phpEmpty(String text) {
        return text.isEmpty() || text.equals("0");
    }

    /**
     * PHP's {@code (int)} cast of text: leading whitespace skipped, then the longest leading number (an optional sign,
     * digits, a fraction and an exponent); 0 when there is none. An integer beyond the range saturates; a number with a
     * fraction or exponent is read as a double and truncated, saturating, and an infinite one is 0.
     */
    public static long phpInt(String text) {
        int n = text.length();
        int i = 0;
        while (i < n && " \t\n\r\u000B\f".indexOf(text.charAt(i)) >= 0) {
            i++;
        }
        int start = i;
        if (i < n && (text.charAt(i) == '-' || text.charAt(i) == '+')) {
            i++;
        }
        int digits = i;
        while (i < n && digit(text.charAt(i))) {
            i++;
        }
        boolean whole = i > digits;
        boolean fractional = false;
        if (i < n && text.charAt(i) == '.' && (whole || (i + 1 < n && digit(text.charAt(i + 1))))) {
            fractional = true;
            i++;
            while (i < n && digit(text.charAt(i))) {
                i++;
            }
        } else if (!whole) {
            return 0;
        }
        if (i < n && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            int exponent = i + 1;
            if (exponent < n && (text.charAt(exponent) == '-' || text.charAt(exponent) == '+')) {
                exponent++;
            }
            if (exponent < n && digit(text.charAt(exponent))) {
                fractional = true;
                i = exponent;
                while (i < n && digit(text.charAt(i))) {
                    i++;
                }
            }
        }
        String number = text.substring(start, i);
        if (!fractional) {
            BigInteger value = new BigInteger(number);
            if (value.bitLength() < Long.SIZE) {
                return value.longValue();
            }
            return value.signum() > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        double value = Double.parseDouble(number);
        return Double.isInfinite(value) ? 0 : (long) value;
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    /** The parent a caller gives: absent, 0 and negative ids mean none, as WordPress casts them. */
    public static Optional<TermId> parent(OptionalLong given) {
        return given.isPresent() && given.getAsLong() > 0
                ? Optional.of(TermId.of(given.getAsLong()))
                : Optional.empty();
    }

    /**
     * Refuses values the columns cannot hold, as WordPress's failed database write reports them: a name or slug longer
     * than 200 characters, and a NUL in the name, the slug or the description (PostgreSQL cannot store one; it is
     * refused on both databases alike).
     */
    public static void requireStorable(String name, String slug, String description, boolean update) {
        if (name.codePointCount(0, name.length()) > MAX_NAME) {
            throw fail(update, "the name is longer than " + MAX_NAME + " characters");
        }
        if (slug.codePointCount(0, slug.length()) > MAX_SLUG) {
            throw fail(update, "the slug is longer than " + MAX_SLUG + " characters");
        }
        if (TermText.hasNul(name)) {
            throw fail(update, "the name holds a NUL character");
        }
        if (TermText.hasNul(slug)) {
            throw fail(update, "the slug holds a NUL character");
        }
        if (TermText.hasNul(description)) {
            throw fail(update, "the description holds a NUL character");
        }
    }

    private static TermException fail(boolean update, String detail) {
        return update ? TermException.dbUpdateError(detail) : TermException.dbInsertError(detail);
    }
}
