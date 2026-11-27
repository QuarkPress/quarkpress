package org.quarkword.lib.wpformatting;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The strings texturize puts in place of straight quotes, primes and dashes, and its list of words that start with
 * an apostrophe ("cockney" words, replaced literally in list order). {@link #english()} gives WordPress's English
 * defaults. A string equal to the ASCII character it replaces is rejected, and so is an empty closing quote
 * (WordPress fails on one). Every string is inserted literally: WordPress reads {@code $1}, {@code ${1}} or
 * {@code \1} in the prime, double-prime, closing-quote and dash strings as a backreference, QuarkWord does not.
 */
public record TexturizeStrings(
        String openingDoubleQuote,
        String closingDoubleQuote,
        String apostrophe,
        String prime,
        String doublePrime,
        String openingSingleQuote,
        String closingSingleQuote,
        String enDash,
        String emDash,
        Map<String, String> cockney) {

    private static final String[] ENGLISH_COCKNEY = {
        "'tain't", "'twere", "'twas", "'tis", "'twill", "'til", "'bout", "'nuff", "'round", "'cause", "'em"
    };
    private static final String ENGLISH_APOSTROPHE = "&#8217;";

    public TexturizeStrings {
        notAscii(openingDoubleQuote, "openingDoubleQuote", "\"");
        notAscii(closingDoubleQuote, "closingDoubleQuote", "\"");
        notEmpty(closingDoubleQuote, "closingDoubleQuote");
        notAscii(apostrophe, "apostrophe", "'");
        notAscii(prime, "prime", "'");
        notAscii(doublePrime, "doublePrime", "\"");
        notAscii(openingSingleQuote, "openingSingleQuote", "'");
        notAscii(closingSingleQuote, "closingSingleQuote", "'");
        notEmpty(closingSingleQuote, "closingSingleQuote");
        notAscii(enDash, "enDash", "-");
        notAscii(emDash, "emDash", "-");
        cockney = copy(cockney);
    }

    /** WordPress's English strings: numeric entities for the curly quotes, primes and dashes. */
    public static TexturizeStrings english() {
        Map<String, String> cockney = new LinkedHashMap<>();
        for (String word : ENGLISH_COCKNEY) {
            cockney.put(word, word.replace("'", ENGLISH_APOSTROPHE));
        }
        return new TexturizeStrings(
                "&#8220;",
                "&#8221;",
                ENGLISH_APOSTROPHE,
                "&#8242;",
                "&#8243;",
                "&#8216;",
                "&#8217;",
                "&#8211;",
                "&#8212;",
                cockney);
    }

    /**
     * A copy whose cockney list is exactly {@code replacements}, in its iteration order. Each entry is one literal
     * pass over each text segment, as in WordPress, so texturizing costs O(length × entries × longest key), with the
     * length as earlier entries have grown it.
     */
    public TexturizeStrings withCockney(Map<String, String> replacements) {
        return new TexturizeStrings(
                openingDoubleQuote,
                closingDoubleQuote,
                apostrophe,
                prime,
                doublePrime,
                openingSingleQuote,
                closingSingleQuote,
                enDash,
                emDash,
                replacements);
    }

    private static void notEmpty(String value, String name) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }

    private static void notAscii(String value, String name, String ascii) {
        Objects.requireNonNull(value, name);
        if (value.equals(ascii)) {
            throw new IllegalArgumentException(name + " must differ from " + ascii);
        }
    }

    private static Map<String, String> copy(Map<String, String> cockney) {
        Objects.requireNonNull(cockney, "cockney");
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : cockney.entrySet()) {
            copy.put(
                    Objects.requireNonNull(e.getKey(), "cockney key"),
                    Objects.requireNonNull(e.getValue(), "cockney value"));
        }
        return Collections.unmodifiableMap(copy);
    }
}
