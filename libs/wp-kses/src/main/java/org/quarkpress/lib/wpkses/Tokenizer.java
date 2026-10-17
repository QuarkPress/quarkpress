package org.quarkpress.lib.wpkses;

/**
 * Splits markup into the tokens kses filters, as one linear scan. At each '<' the first matching kind wins: a
 * comment that ends on its line, a closing tag with an invalid name, a markup declaration, any tag-like span up to
 * the next '>' or the end. A lone '>' is a token too. Text between tokens is copied unchanged.
 */
final class Tokenizer {

    /** Receives each token and appends its replacement. */
    @FunctionalInterface
    interface TokenHandler {
        void handle(String token, StringBuilder out);
    }

    private Tokenizer() {}

    static String rewrite(String content, TokenHandler handler) {
        int n = content.length();
        StringBuilder out = new StringBuilder(n);
        Finder greaterThan = new Finder(content, ">");
        Finder commentClose = new Finder(content, "-->");
        Finder lineFeed = new Finder(content, "\n");
        int copied = 0;
        for (int i = 0; i < n; i++) {
            char c = content.charAt(i);
            if (c != '<' && c != '>') {
                continue;
            }
            int end = c == '>' ? i + 1 : tagEnd(content, i, greaterThan, commentClose, lineFeed);
            out.append(content, copied, i);
            handler.handle(content.substring(i, end), out);
            copied = end;
            i = end - 1;
        }
        return out.append(content, copied, n).toString();
    }

    /** End (exclusive) of the token that starts with the '<' at {@code i}. */
    private static int tagEnd(String s, int i, Finder greaterThan, Finder commentClose, Finder lineFeed) {
        int n = s.length();
        if (s.startsWith("<!--", i)) {
            int end = commentEnd(s, i + 4, commentClose, lineFeed);
            if (end >= 0) {
                return end;
            }
        }
        int gt = greaterThan.next(i + 1);
        if (i + 2 < n && s.charAt(i + 1) == '/' && !TextUtils.isAsciiLetter(s.charAt(i + 2))) {
            // The character after "</" may itself be '>'; the token then needs a later '>'.
            int close = gt == i + 2 ? greaterThan.next(i + 3) : gt;
            if (close >= 0) {
                return close + 1;
            }
        }
        return gt >= 0 ? gt + 1 : n;
    }

    /**
     * The comment ends after the first "-->", provided no line feed comes before it; without "-->" it ends at the
     * end of the input or just before a line feed that is the last character. -1 when it does not match.
     */
    private static int commentEnd(String s, int from, Finder commentClose, Finder lineFeed) {
        int close = commentClose.next(from);
        int lf = lineFeed.next(from);
        if (close >= 0 && (lf < 0 || close < lf)) {
            return close + 3;
        }
        if (lf < 0) {
            return s.length();
        }
        return lf == s.length() - 1 ? lf : -1;
    }
}
