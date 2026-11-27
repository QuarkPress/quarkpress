package org.quarkword.lib.wpformatting;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * How {@link Excerpts#trimExcerpt} builds an excerpt from a post's content: the caller's hooks for the steps that need
 * other libraries (shortcodes, blocks, the content filters), the excerpt length and the "more" text. The defaults are
 * WordPress's in en_US with no shortcodes, blocks or content filters. Immutable and thread-safe when the hooks are.
 */
public final class ExcerptOptions {

    private static final ExcerptOptions DEFAULTS = new Builder().build();

    private final UnaryOperator<String> shortcodeStripper;
    private final UnaryOperator<String> blockRemover;
    private final UnaryOperator<String> contentFilter;
    private final int length;
    private final String more;
    private final WordCountType wordCountType;
    private final boolean utf8Charset;

    private ExcerptOptions(Builder builder) {
        this.shortcodeStripper = builder.shortcodeStripper;
        this.blockRemover = builder.blockRemover;
        this.contentFilter = builder.contentFilter;
        this.length = builder.length;
        this.more = builder.more;
        this.wordCountType = builder.wordCountType;
        this.utf8Charset = builder.utf8Charset;
    }

    /** WordPress's defaults: no hooks, 55 words, {@code " [&hellip;]"}, words, a UTF-8 blog. */
    public static ExcerptOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The {@code strip_shortcodes} hook. */
    public UnaryOperator<String> shortcodeStripper() {
        return shortcodeStripper;
    }

    /** The {@code excerpt_remove_blocks} hook. */
    public UnaryOperator<String> blockRemover() {
        return blockRemover;
    }

    /** The {@code the_content} hook run on the stripped content. */
    public UnaryOperator<String> contentFilter() {
        return contentFilter;
    }

    /** The {@code excerpt_length}, in words or characters. */
    public int length() {
        return length;
    }

    /** The {@code excerpt_more} text appended to a shortened excerpt. */
    public String more() {
        return more;
    }

    /** The locale's word-count type. */
    public WordCountType wordCountType() {
        return wordCountType;
    }

    /** Whether the blog charset is UTF-8. */
    public boolean utf8Charset() {
        return utf8Charset;
    }

    /** Builds {@link ExcerptOptions}; every setting starts at WordPress's default. */
    public static final class Builder {

        private UnaryOperator<String> shortcodeStripper = UnaryOperator.identity();
        private UnaryOperator<String> blockRemover = UnaryOperator.identity();
        private UnaryOperator<String> contentFilter = UnaryOperator.identity();
        private int length = Excerpts.DEFAULT_WORDS;
        private String more = Excerpts.DEFAULT_EXCERPT_MORE;
        private WordCountType wordCountType = WordCountType.WORDS;
        private boolean utf8Charset = true;

        private Builder() {}

        /** WordPress's {@code strip_shortcodes} with the site's registered shortcodes; the default changes nothing. */
        public Builder shortcodeStripper(UnaryOperator<String> f) {
            this.shortcodeStripper = Objects.requireNonNull(f, "shortcodeStripper");
            return this;
        }

        /** WordPress's {@code excerpt_remove_blocks}; the default changes nothing. */
        public Builder blockRemover(UnaryOperator<String> f) {
            this.blockRemover = Objects.requireNonNull(f, "blockRemover");
            return this;
        }

        /**
         * The site's {@code the_content} filters without {@code do_blocks} and {@code wp_filter_content_tags}; the
         * default changes nothing.
         */
        public Builder contentFilter(UnaryOperator<String> f) {
            this.contentFilter = Objects.requireNonNull(f, "contentFilter");
            return this;
        }

        /** The {@code excerpt_length}, in words or characters; default 55. */
        public Builder length(int words) {
            this.length = words;
            return this;
        }

        /**
         * The {@code excerpt_more} text appended to a shortened excerpt; {@code null} means the default. (In
         * WordPress an {@code excerpt_more} filter that returns null ends up as {@code &hellip;}; pass that string to
         * get the same result.)
         */
        public Builder more(String more) {
            this.more = more == null ? Excerpts.DEFAULT_EXCERPT_MORE : more;
            return this;
        }

        /** The locale's word-count type; default {@link WordCountType#WORDS}. */
        public Builder wordCountType(WordCountType type) {
            this.wordCountType = Objects.requireNonNull(type, "wordCountType");
            return this;
        }

        /** Whether the blog charset is UTF-8, which character counting needs; default true. */
        public Builder utf8Charset(boolean utf8) {
            this.utf8Charset = utf8;
            return this;
        }

        public ExcerptOptions build() {
            return new ExcerptOptions(this);
        }
    }
}
