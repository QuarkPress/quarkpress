package org.quarkpress.media.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * WordPress's MIME-type filter of attachment queries: each value is a comma-separated list (empty pieces kept), each
 * piece becomes a pattern compared with the attachment's type, and the patterns combine with OR. Whitespace is
 * removed; a value with a slash keeps {@code -*.a-zA-Z0-9} in its group (before the first slash) and also {@code +} in
 * its subtype (the rest), an empty subtype meaning {@code *}; a value without a slash keeps {@code -*.a-zA-Z0-9} and
 * gets {@code /*} unless it holds a star. Each run of stars becomes one SQL wildcard {@code %}; a pattern with a
 * wildcard is compared with LIKE, any other for equality. As in WordPress, a piece that is empty, {@code %} or {@code
 * %/%} once its whitespace is removed drops the whole filter, whatever the other pieces; so does a trailing comma, a
 * piece of whitespace only or {@code " %"}.
 */
public final class MimeFilter {

    /** The most pieces one filter may hold, so that a query binds a bounded number of parameters. */
    public static final int MAX_VALUES = 1000;

    /** One pattern: {@code value} with {@code %} for each wildcard, compared with LIKE when {@code like}. */
    public record MimeClause(String value, boolean like) {
        public MimeClause {
            Objects.requireNonNull(value, "value");
        }
    }

    private MimeFilter() {}

    /**
     * The patterns of {@code values}, each split on commas, in order; empty when the filter restricts nothing (no
     * value, or a piece that is empty, {@code %} or {@code %/%}).
     *
     * @throws IllegalArgumentException when the values hold more than {@link #MAX_VALUES} pieces
     */
    public static Optional<List<MimeClause>> parse(List<String> given) {
        List<String> values = pieces(given);
        for (String value : values) {
            String bare = withoutWhitespace(value);
            if (bare.isEmpty() || bare.equals("%") || bare.equals("%/%")) {
                return Optional.empty();
            }
        }
        if (values.isEmpty()) {
            return Optional.empty();
        }
        List<MimeClause> clauses = new ArrayList<>(values.size());
        for (String value : values) {
            clauses.add(clause(value));
        }
        return Optional.of(List.copyOf(clauses));
    }

    /** The value without ASCII whitespace (space, tab, line feed, vertical tab, form feed, carriage return). */
    private static String withoutWhitespace(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != ' ' && (c < '\t' || c > '\r')) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Every value split on commas, empty pieces kept; at most {@link #MAX_VALUES} pieces. */
    private static List<String> pieces(List<String> given) {
        List<String> pieces = new ArrayList<>();
        for (String value : given) {
            int start = 0;
            while (true) {
                if (pieces.size() == MAX_VALUES) {
                    throw new IllegalArgumentException("at most " + MAX_VALUES + " MIME types may be given");
                }
                int comma = value.indexOf(',', start);
                if (comma < 0) {
                    pieces.add(value.substring(start));
                    break;
                }
                pieces.add(value.substring(start, comma));
                start = comma + 1;
            }
        }
        return pieces;
    }

    private static MimeClause clause(String value) {
        int slash = value.indexOf('/');
        StringBuilder pattern = new StringBuilder(value.length() + 2);
        if (slash >= 0) {
            keep(value, 0, slash, false, pattern);
            pattern.append('/');
            int subtypeStart = pattern.length();
            keep(value, slash + 1, value.length(), true, pattern);
            if (pattern.length() == subtypeStart) {
                pattern.append('*');
            }
        } else {
            keep(value, 0, value.length(), false, pattern);
            if (pattern.indexOf("*") < 0) {
                pattern.append("/*");
            }
        }
        return wildcards(pattern);
    }

    /** Appends the characters of {@code value[from, to)} a group (or, with {@code subtype}, a subtype) keeps. */
    private static void keep(String value, int from, int to, boolean subtype, StringBuilder into) {
        for (int i = from; i < to; i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '*'
                    || c == '.'
                    || (subtype && c == '+')) {
                into.append(c);
            }
        }
    }

    /** Each run of stars as one {@code %}; LIKE when there was one. */
    private static MimeClause wildcards(CharSequence pattern) {
        StringBuilder value = new StringBuilder(pattern.length());
        boolean like = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c != '*') {
                value.append(c);
            } else if (i == 0 || pattern.charAt(i - 1) != '*') {
                value.append('%');
                like = true;
            }
        }
        return new MimeClause(value.toString(), like);
    }
}
