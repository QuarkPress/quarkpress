package org.quarkword.lib.wpformatting;

import java.util.ArrayList;
import java.util.List;

/**
 * wpautop's protection of {@code <pre>} blocks: each one is swapped for a placeholder before formatting and put
 * back afterwards, as WordPress does it, including its handling of stray and nested closers and of placeholders an
 * author typed.
 */
final class PreBlocks {

    private static final String OPEN = "<pre";
    private static final String CLOSE = "</pre>";
    private static final String PLACEHOLDER_START = "<pre wp-pre-tag-";
    private static final String PLACEHOLDER_END = "></pre>";

    private final String text;
    private final List<String> blocks;

    private PreBlocks(String text, List<String> blocks) {
        this.text = text;
        this.blocks = blocks;
    }

    /** The text with its blocks swapped out, or {@code null} when the text has no {@code <pre}. */
    static PreBlocks protect(String text) {
        if (!text.contains(OPEN)) {
            return null;
        }
        Forward open = new Forward(text, OPEN);
        StringBuilder out = new StringBuilder(text.length() + 32);
        List<String> blocks = new ArrayList<>();
        int segment = 0;
        int close;
        while ((close = text.indexOf(CLOSE, segment)) >= 0) {
            int start = open.from(segment);
            if (start >= 0 && start < close) {
                out.append(text, segment, start).append(placeholder(blocks.size()));
                blocks.add(text.substring(start, close) + CLOSE);
            } else {
                // a segment without an opener loses its closer
                out.append(text, segment, close);
            }
            segment = close + CLOSE.length();
        }
        out.append(text, segment, text.length());
        return new PreBlocks(out.toString(), List.copyOf(blocks));
    }

    String text() {
        return text;
    }

    /**
     * Puts the blocks back. WordPress replaces placeholder 0 everywhere, then 1, and so on, so a block put back
     * early gets the later placeholders inside it replaced too, while the earlier ones and its own stay literal.
     * The result's length is worked out first; above {@code limit} chars nothing is built and
     * {@link FormattingLimitException} is thrown.
     */
    String restore(String formatted, long limit) {
        int count = blocks.size();
        long[] lengths = new long[count];
        for (int i = count - 1; i >= 0; i--) {
            lengths[i] = expandedLength(blocks.get(i), i + 1, lengths);
        }
        long total = expandedLength(formatted, 0, lengths);
        if (total > limit) {
            throw new FormattingLimitException("wpautop", total, limit);
        }
        String[] expanded = new String[count];
        for (int i = count - 1; i >= 0; i--) {
            expanded[i] = substitute(blocks.get(i), i + 1, expanded);
        }
        return substitute(formatted, 0, expanded);
    }

    static String placeholder(int index) {
        return PLACEHOLDER_START + index + PLACEHOLDER_END;
    }

    /**
     * The length {@code s} reaches once each placeholder whose index is at least {@code min} is replaced by its
     * expanded block, whose length is in {@code lengths}; saturates at {@link Long#MAX_VALUE}.
     */
    private static long expandedLength(String s, int min, long[] lengths) {
        long length = s.length();
        int from = 0;
        int found;
        while ((found = s.indexOf(PLACEHOLDER_START, from)) >= 0) {
            int end = placeholderEnd(s, found, min, lengths.length);
            if (end >= 0) {
                int index = index(s, found + PLACEHOLDER_START.length(), end - PLACEHOLDER_END.length());
                length = saturatedAdd(length - (end - found), lengths[index]);
                from = end;
            } else {
                from = found + 1;
            }
        }
        return length;
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    /** Replaces each placeholder whose index is at least {@code min} with its expanded block. */
    private static String substitute(String s, int min, String[] expanded) {
        StringBuilder out = null;
        int copied = 0;
        int from = 0;
        int found;
        while ((found = s.indexOf(PLACEHOLDER_START, from)) >= 0) {
            int end = placeholderEnd(s, found, min, expanded.length);
            if (end >= 0) {
                if (out == null) {
                    out = new StringBuilder(s.length() + 64);
                }
                int index = index(s, found + PLACEHOLDER_START.length(), end - PLACEHOLDER_END.length());
                out.append(s, copied, found).append(expanded[index]);
                from = end;
                copied = from;
            } else {
                from = found + 1;
            }
        }
        if (out == null) {
            return s;
        }
        return out.append(s, copied, s.length()).toString();
    }

    /**
     * The end of the placeholder starting at {@code found} when its index is in [{@code min}, {@code count}), or
     * -1.
     */
    private static int placeholderEnd(String s, int found, int min, int count) {
        int digits = found + PLACEHOLDER_START.length();
        int end = digits;
        while (end < s.length() && Chars.isAsciiDigit(s.charAt(end)) && end - digits < 10) {
            end++;
        }
        int index = index(s, digits, end);
        if (index >= min && index < count && s.startsWith(PLACEHOLDER_END, end)) {
            return end + PLACEHOLDER_END.length();
        }
        return -1;
    }

    /** The decimal index written at [start, end), as PHP would print it (no leading zero), or -1. */
    private static int index(String s, int start, int end) {
        int length = end - start;
        if (length == 0 || length > 9 || (length > 1 && s.charAt(start) == '0')) {
            return -1;
        }
        return Integer.parseInt(s, start, end, 10);
    }
}
