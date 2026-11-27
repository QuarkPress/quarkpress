package org.quarkword.lib.wpformatting;

/**
 * Decides the quotes left after the opening-quote rules: primes after digits and closing quotes before a
 * boundary. The text is cut at each opening quote into sentences; a sentence after an opening quote that holds
 * no closing quote yet gets exactly one closing quote where it can.
 */
final class Primes {

    /** Marks a quote before a boundary while a sentence is decided. */
    private static final String FLAG = "<\u0001flag>";

    private Primes() {}

    /**
     * {@code haystack} with each {@code needle} decided. {@code openQuote} is the opening-quote sentinel the
     * sentences are cut at; it is kept as it is.
     */
    static String apply(String haystack, char needle, String prime, String openQuote, String closeQuote) {
        StringBuilder out = new StringBuilder(haystack.length() + 16);
        int start = 0;
        boolean first = true;
        while (true) {
            int at = haystack.indexOf(openQuote, start);
            String sentence = haystack.substring(start, at < 0 ? haystack.length() : at);
            out.append(sentence(sentence, first, needle, prime, closeQuote));
            if (at < 0) {
                return out.toString();
            }
            out.append(openQuote);
            start = at + openQuote.length();
            first = false;
        }
    }

    private static String sentence(String s, boolean first, char needle, String prime, String close) {
        if (s.indexOf(needle) < 0) {
            return s;
        }
        String quote = String.valueOf(needle);
        if (!first && !s.contains(close)) {
            int[] count = {0};
            String flagged = replaceBeforeBoundary(s, needle, FLAG, count);
            if (count[0] == 1) {
                s = afterDigit(flagged.replace(FLAG, close), quote, prime);
            } else if (count[0] == 0) {
                s = afterDigit(flagged, quote, prime);
            } else {
                int[] closed = {0};
                s = notAfterDigit(flagged, FLAG, close, closed);
                if (closed[0] == 0) {
                    int pick = s.lastIndexOf(FLAG + ".");
                    if (pick < 0) {
                        pick = s.lastIndexOf(FLAG);
                    }
                    s = s.substring(0, pick) + close + s.substring(pick + FLAG.length());
                }
                s = afterDigit(s, quote, prime);
                s = afterDigit(s, FLAG, prime);
                s = s.replace(FLAG, close);
            }
        } else {
            s = afterDigit(s, quote, prime);
            s = replaceBeforeBoundary(s, needle, close, new int[1]);
        }
        return needle == '"' ? s.replace(quote, close) : s;
    }

    /** Each {@code needle} before a boundary becomes {@code replacement}; {@code count[0]} gets their number. */
    private static String replaceBeforeBoundary(String s, char needle, String replacement, int[] count) {
        StringBuilder out = null;
        int copied = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            if (QuoteRules.boundaryAfter(s, i + 1)) {
                out = Edits.started(out, s);
                out.append(s, copied, i).append(replacement);
                copied = i + 1;
                count[0]++;
            }
        }
        return Edits.finished(out, s, copied);
    }

    /** Each {@code target} right after an ASCII digit becomes {@code replacement}. */
    private static String afterDigit(String s, String target, String replacement) {
        return replace(s, target, replacement, true, new int[1]);
    }

    /** Each {@code target} not right after an ASCII digit becomes {@code replacement}. */
    private static String notAfterDigit(String s, String target, String replacement, int[] count) {
        return replace(s, target, replacement, false, count);
    }

    private static String replace(String s, String target, String replacement, boolean afterDigit, int[] count) {
        StringBuilder out = null;
        int copied = 0;
        for (int i = s.indexOf(target); i >= 0; ) {
            if (QuoteRules.digitAt(s, i - 1) == afterDigit) {
                out = Edits.started(out, s);
                out.append(s, copied, i).append(replacement);
                copied = i + target.length();
                count[0]++;
                i = s.indexOf(target, copied);
            } else {
                i = s.indexOf(target, i + 1);
            }
        }
        return Edits.finished(out, s, copied);
    }
}
