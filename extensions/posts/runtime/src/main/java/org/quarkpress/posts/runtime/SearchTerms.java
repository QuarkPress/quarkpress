package org.quarkpress.posts.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A search string split into terms as WordPress splits it: a token is a double-quoted phrase (to the next double quote
 * or the end) or a run of characters other than space, tab, comma, plus and double quote. Each token loses its quotes
 * (a phrase keeps its inner and outer spaces); empty tokens, {@code "0"}, single ASCII letters, a lone hyphen and
 * the English stopwords are dropped. When nothing remains, or more than nine terms do, the whole string (without line
 * breaks) is the only term. A term starting with a hyphen excludes what follows it.
 */
public final class SearchTerms {

    /** WordPress's English stopwords. */
    public static final List<String> STOPWORDS = List.of(
            "about", "an", "are", "as", "at", "be", "by", "com", "for", "from", "how", "in", "is", "it", "of", "on",
            "or", "that", "the", "this", "to", "was", "what", "when", "where", "who", "will", "with", "www");

    /**
     * The longest search string, in bytes, that WordPress searches for: a longer one is replaced by an empty string
     * before parsing. The query applies this limit, {@link #parse} does not.
     */
    public static final int MAX_BYTES = 1600;

    private static final int MAX_TERMS = 9;

    private SearchTerms() {}

    /** The parsed search: the string without line breaks, its terms, and how many raw tokens it held. */
    public record Parsed(String cleaned, List<String> terms, int rawCount) {

        public Parsed {
            Objects.requireNonNull(cleaned, "cleaned");
            terms = List.copyOf(terms);
        }

        /** The terms that must match, in order. */
        public List<String> positive() {
            return terms.stream().filter(term -> !term.startsWith("-")).toList();
        }

        /** The text of the terms that must not match (their leading hyphen removed), in order. */
        public List<String> excluded() {
            return terms.stream()
                    .filter(term -> term.startsWith("-"))
                    .map(term -> term.substring(1))
                    .toList();
        }

        /** Whether a hyphen starts the string or follows whitespace, which turns the whole-string ranks off. */
        public boolean sentenceRankSkipped() {
            for (int i = 0; i < cleaned.length(); i++) {
                if (cleaned.charAt(i) == '-' && (i == 0 || isWhitespace(cleaned.charAt(i - 1)))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Splits a search string into terms; with {@code sentence} the whole string is the only term. The byte limit
     * {@link #MAX_BYTES} is not applied here.
     */
    public static Parsed parse(String search, boolean sentence) {
        Objects.requireNonNull(search, "search");
        String cleaned = search.replace("\r", "").replace("\n", "");
        if (sentence) {
            return new Parsed(cleaned, List.of(cleaned), 1);
        }
        List<String> tokens = tokens(cleaned);
        if (tokens.isEmpty()) {
            return new Parsed(cleaned, List.of(cleaned), 1);
        }
        List<String> terms = new ArrayList<>();
        for (String token : tokens) {
            String term = clean(token);
            if (!PostRules.phpEmpty(term) && !singleLetterOrHyphen(term) && !isStopword(term)) {
                terms.add(term);
            }
        }
        if (terms.isEmpty() || terms.size() > MAX_TERMS) {
            terms = List.of(cleaned);
        }
        return new Parsed(cleaned, terms, tokens.size());
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                int close = text.indexOf('"', i + 1);
                int end = close < 0 ? text.length() : close + 1;
                tokens.add(text.substring(i, end));
                i = end;
            } else if (isSeparator(c)) {
                i++;
            } else {
                int end = i;
                while (end < text.length() && !isSeparator(text.charAt(end)) && text.charAt(end) != '"') {
                    end++;
                }
                tokens.add(text.substring(i, end));
                i = end;
            }
        }
        return tokens;
    }

    private static String clean(String token) {
        boolean phrase = token.length() >= 3 && token.startsWith("\"") && token.endsWith("\"");
        int start = 0;
        int end = token.length();
        while (start < end && trimmed(token.charAt(start), phrase)) {
            start++;
        }
        while (end > start && trimmed(token.charAt(end - 1), phrase)) {
            end--;
        }
        return token.substring(start, end);
    }

    private static boolean trimmed(char c, boolean phrase) {
        return c == '"' || c == '\'' || (!phrase && c == ' ');
    }

    private static boolean singleLetterOrHyphen(String term) {
        if (term.length() != 1) {
            return false;
        }
        char c = term.charAt(0);
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-';
    }

    private static boolean isStopword(String term) {
        return STOPWORDS.contains(term.toLowerCase(Locale.ROOT));
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == '\t' || c == ',' || c == '+';
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\f';
    }
}
