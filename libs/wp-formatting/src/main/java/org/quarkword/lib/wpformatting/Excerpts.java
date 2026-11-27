package org.quarkword.lib.wpformatting;

import java.util.Objects;

/**
 * WordPress's excerpt functions: {@code wp_trim_words}, {@code wp_html_excerpt}, {@code excerpt_remove_footnotes} and
 * the excerpt {@code wp_trim_excerpt} generates from a post's content. Stateless; a {@code more} of {@code null} means
 * WordPress's default.
 */
public final class Excerpts {

    /** The default number of words of {@code wp_trim_words} and of a generated excerpt. */
    public static final int DEFAULT_WORDS = 55;

    /** {@code wp_trim_words}' default "more" text. */
    public static final String DEFAULT_MORE = "&hellip;";

    /** {@code excerpt_more}'s default text. */
    public static final String DEFAULT_EXCERPT_MORE = " [&hellip;]";

    private Excerpts() {}

    /** {@code wp_trim_words} in words, with the default "more" text. */
    public static String trimWords(String text, int numWords) {
        return trimWords(text, numWords, null);
    }

    /** {@code wp_trim_words} in words. */
    public static String trimWords(String text, int numWords, String more) {
        return trimWords(text, numWords, more, WordCountType.WORDS, true);
    }

    /**
     * {@code wp_trim_words}: the text without tags and trimmed, cut to {@code numWords} words joined by single spaces,
     * with {@code more} appended when it was cut. Only CR, LF, tab and space separate words. Characters are counted
     * instead when {@code type} is a character type and {@code utf8Charset} is true. A negative {@code numWords} gives
     * WordPress's answer: in words, -1 and -2 keep all words but the last and lower values keep none; in characters,
     * {@code numWords + 1} code points are dropped from the end and then one more.
     */
    public static String trimWords(String text, int numWords, String more, WordCountType type, boolean utf8Charset) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(type, "type");
        boolean characters = type != WordCountType.WORDS && utf8Charset;
        return TrimWords.apply(text, numWords, more == null ? DEFAULT_MORE : more, characters);
    }

    /** {@code wp_html_excerpt} with no "more" text. */
    public static String htmlExcerpt(String text, int count) {
        return htmlExcerpt(text, count, null);
    }

    /**
     * {@code wp_html_excerpt}: the text without tags and with whitespace runs collapsed, cut to {@code count} code
     * points (a negative count drops that many from the end) with a partial character reference at the cut removed;
     * when that is shorter than the whole text it is trimmed and {@code more} appended.
     */
    public static String htmlExcerpt(String text, int count, String more) {
        Objects.requireNonNull(text, "text");
        return HtmlExcerpt.excerpt(text, count, more == null ? "" : more);
    }

    /** {@code excerpt_remove_footnotes}: the content without the block editor's footnote markers. */
    public static String removeFootnotes(String content) {
        Objects.requireNonNull(content, "content");
        return Footnotes.remove(content);
    }

    /**
     * The excerpt {@code wp_trim_excerpt} gives. A {@code rawExcerpt} that is not blank after a PHP trim is a manual
     * excerpt and is returned unchanged. Otherwise the excerpt is built from {@code content}: the shortcode stripper,
     * the block remover, footnote removal and the content filter run in that order, every {@code ]]>} becomes
     * {@code ]]&gt;}, and the text is trimmed to the options' length with their "more" text.
     */
    public static String trimExcerpt(String rawExcerpt, String content, ExcerptOptions options) {
        Objects.requireNonNull(rawExcerpt, "rawExcerpt");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(options, "options");
        if (!Formatting.phpTrim(rawExcerpt).isEmpty()) {
            return rawExcerpt;
        }
        String text = hook(options.shortcodeStripper().apply(content), "shortcodeStripper");
        text = hook(options.blockRemover().apply(text), "blockRemover");
        text = Footnotes.remove(text);
        text = hook(options.contentFilter().apply(text), "contentFilter");
        text = text.replace("]]>", "]]&gt;");
        return trimWords(text, options.length(), options.more(), options.wordCountType(), options.utf8Charset());
    }

    private static String hook(String result, String name) {
        return Objects.requireNonNull(result, () -> name + " returned null");
    }
}
