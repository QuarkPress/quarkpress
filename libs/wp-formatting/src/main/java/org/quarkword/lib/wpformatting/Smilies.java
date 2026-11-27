package org.quarkword.lib.wpformatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code convert_smilies}: replaces WordPress's 43 default smiley codes in the text outside tags with raw emoji, or
 * with an {@code <img>} for {@code :mrgreen:}. A code is replaced only with a line start or a space before it and a
 * line end or a space after it; only a line feed ends a line, and the spaces are CR, LF, tab, space, no-break space
 * and the text {@code &nbsp;}. Codes are case-sensitive, and nothing is replaced inside {@code code}, {@code pre},
 * {@code style}, {@code script} or {@code textarea} elements. Immutable and thread-safe.
 */
public final class Smilies {

    private final boolean enabled;
    private final List<String> replacements;

    private Smilies(Builder builder) {
        this.enabled = builder.enabled;
        if (!enabled) {
            this.replacements = List.of();
            return;
        }
        if (builder.imageBaseUrl == null) {
            throw new IllegalStateException("imageBaseUrl is required when smilies are enabled");
        }
        List<String> built = new ArrayList<>();
        for (SmileyTable.Smiley smiley : SmileyTable.MATCH_ORDER) {
            built.add(
                    SmileyTable.isImageFile(smiley.replacement())
                            ? image(builder.imageBaseUrl, smiley.replacement(), smiley.code())
                            : smiley.replacement());
        }
        this.replacements = List.copyOf(built);
    }

    /**
     * An enabled converter whose smiley images live under {@code imageBaseUrl}, WordPress's
     * {@code includes_url('images/smilies/')} such as {@code https://example.com/wp-includes/images/smilies/}.
     */
    public static Smilies wordPress(String imageBaseUrl) {
        return builder().imageBaseUrl(imageBaseUrl).build();
    }

    /** A builder: enabled, as WordPress installs, and with no image base URL yet. */
    public static Builder builder() {
        return new Builder();
    }

    /** The text with its smileys replaced; the text itself when disabled or when it holds no smiley. */
    public String apply(String text) {
        Objects.requireNonNull(text, "text");
        if (!enabled || text.isEmpty()) {
            return text;
        }
        return SmileyWalker.convert(text, replacements);
    }

    /** The 43 codes, grouped by first character and longest first within each group. */
    public static List<String> codes() {
        return SmileyTable.CODES;
    }

    private static String image(String base, String file, String code) {
        return "<img src=\"" + base + file + "\" alt=\"" + code
                + "\" class=\"wp-smiley\" style=\"height: 1em; max-height: 1em;\" />";
    }

    /** Settings for a {@link Smilies}. */
    public static final class Builder {

        private boolean enabled = true;
        private String imageBaseUrl;

        private Builder() {}

        /** WordPress's {@code use_smilies} option: false makes {@link Smilies#apply} return its input. Default true. */
        public Builder enabled(boolean useSmilies) {
            this.enabled = useSmilies;
            return this;
        }

        /**
         * The URL the image file name of an image smiley is appended to, used verbatim: the caller passes it
         * already escaped for an HTML attribute. Required when enabled.
         */
        public Builder imageBaseUrl(String url) {
            this.imageBaseUrl = Objects.requireNonNull(url, "url");
            return this;
        }

        /** The converter; throws {@link IllegalStateException} when enabled without an image base URL. */
        public Smilies build() {
            return new Smilies(this);
        }
    }
}
