package org.quarkword.lib.wpformatting;

import java.util.Objects;

/**
 * What a locale counts when {@code wp_trim_words} shortens text: words, or characters. WordPress trims both character
 * types the same way: the single spaces left after whitespace is collapsed count as characters in either.
 */
public enum WordCountType {
    /** Words separated by runs of CR, LF, tab and space: English and most locales. */
    WORDS,
    /** WordPress's {@code characters_excluding_spaces}. */
    CHARACTERS_EXCLUDING_SPACES,
    /** WordPress's {@code characters_including_spaces}. */
    CHARACTERS_INCLUDING_SPACES;

    private static final String CHARACTERS = "characters";

    /**
     * The type for WordPress's name of it, as a locale's {@code wp_get_word_count_type} gives it. A name that starts
     * with {@code characters} (case-sensitive) is a character type, {@link #CHARACTERS_INCLUDING_SPACES} unless it is
     * {@code characters_excluding_spaces}; any other name is {@link #WORDS}.
     */
    public static WordCountType fromWordPress(String type) {
        Objects.requireNonNull(type, "type");
        if (!type.startsWith(CHARACTERS)) {
            return WORDS;
        }
        return type.equals("characters_excluding_spaces") ? CHARACTERS_EXCLUDING_SPACES : CHARACTERS_INCLUDING_SPACES;
    }
}
