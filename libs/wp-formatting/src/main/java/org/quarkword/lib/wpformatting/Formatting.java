package org.quarkword.lib.wpformatting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** WordPress's stateless text formatting functions, with WordPress's output. */
public final class Formatting {

    private Formatting() {}

    /**
     * {@code wp_html_split}: the text as alternating text and markup chunks (text at even indexes, markup at odd
     * ones), starting and ending with a possibly empty text chunk. Joined, the chunks give back the input.
     */
    public static List<String> htmlSplit(String input) {
        Objects.requireNonNull(input, "input");
        return List.copyOf(HtmlSplitter.split(input));
    }

    /**
     * {@code wp_replace_in_html_tags}: replaces text inside markup only (tags, comments and CDATA), never in the
     * text between them. With one pair every occurrence of its key is replaced; with several, all are applied at
     * once and the longest key wins at each position. Empty keys are ignored. Returns {@code haystack} itself when
     * nothing changed.
     */
    public static String replaceInHtmlTags(String haystack, Map<String, String> pairs) {
        Objects.requireNonNull(haystack, "haystack");
        Objects.requireNonNull(pairs, "pairs");
        Map<String, String> table = new LinkedHashMap<>();
        for (Map.Entry<String, String> pair : pairs.entrySet()) {
            String key = Objects.requireNonNull(pair.getKey(), "pairs key");
            String value = Objects.requireNonNull(pair.getValue(), "pairs value");
            if (!key.isEmpty()) {
                table.put(key, value);
            }
        }
        if (table.isEmpty()) {
            return haystack;
        }
        StringBuilder out = new StringBuilder();
        boolean[] changed = {false};
        HtmlSplitter.forEachChunk(haystack, (start, end, markup) -> {
            if (!markup) {
                out.append(haystack, start, end);
                return;
            }
            String chunk = haystack.substring(start, end);
            String replaced = Strtr.apply(chunk, table);
            if (!replaced.equals(chunk)) {
                changed[0] = true;
            }
            out.append(replaced);
        });
        return changed[0] ? out.toString() : haystack;
    }

    /**
     * {@code wpautop} with line breaks on: blank lines become paragraphs and single newlines {@code <br />}, with
     * WordPress's handling of block tags, {@code <pre>} blocks, script, style, svg and math, and markup. Text that
     * holds only spaces, tabs, line feeds, carriage returns, NULs and vertical tabs gives {@code ""}; any other
     * result ends with a line feed.
     */
    public static String autop(String text) {
        return autop(text, true);
    }

    /**
     * {@code wpautop}: as {@link #autop(String)}; with {@code br} false single newlines stay as they are. Line
     * breaks right after a block tag or right before a paragraph, list or table tag are removed either way. The
     * default output limit of {@link AutopOptions} applies.
     */
    public static String autop(String text, boolean br) {
        Objects.requireNonNull(text, "text");
        return Autop.autop(text, br, AutopOptions.defaults().outputLimit(text.length()));
    }

    /**
     * {@code wpautop} with the given options. Throws {@link FormattingLimitException} when the text it would build
     * is longer than the options' output limit; with {@code <pre>} blocks this is known, and thrown, before the
     * blocks are put back.
     */
    public static String autop(String text, AutopOptions options) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(options, "options");
        return Autop.autop(text, options.lineBreaks(), options.outputLimit(text.length()));
    }

    /**
     * {@code convert_chars}: each {@code &} becomes {@code &#038;} unless it ends the text, is followed by
     * {@code #}, or looks like a named reference. That test takes the char after {@code &} as it is and then wants
     * 1 to 8 ASCII letters or digits 1 to 4 and a {@code ;}, so {@code &abcdefghi;} is kept and {@code &a;} is
     * not. An {@code &} before a non-ASCII char is always escaped. The char after an {@code &} is never itself
     * checked, so {@code &&amp;} is kept. Text without {@code &} is returned as it is.
     */
    public static String convertChars(String content) {
        Objects.requireNonNull(content, "content");
        return ConvertChars.apply(content);
    }

    /**
     * {@code capital_P_dangit} with WordPress's English opening curly double quote ({@code &#8220;}). See
     * {@link #capitalPDangit(String, CapitalPMode, TexturizeStrings)}.
     */
    public static String capitalPDangit(String text, CapitalPMode mode) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(mode, "mode");
        return CapitalP.apply(text, mode, CapitalP.ENGLISH_OPENING_DOUBLE_QUOTE);
    }

    /**
     * {@code capital_P_dangit}: corrects the case-sensitive spelling "Wordpress" to "WordPress". In
     * {@link CapitalPMode#SIMPLE} mode every occurrence is corrected, even inside a word. In
     * {@link CapitalPMode#JUDICIOUS} mode only those right after a space, {@code &#8216;}, the opening double quote of
     * {@code strings}, {@code >} or {@code (} are, one prefix after the other in that order; a "Wordpress" at the
     * start of the text stays. Text without "Wordpress" is returned as it is.
     */
    public static String capitalPDangit(String text, CapitalPMode mode, TexturizeStrings strings) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(strings, "strings");
        return CapitalP.apply(text, mode, strings.openingDoubleQuote());
    }

    /** PHP's {@code trim}: removes spaces, tabs, line feeds, carriage returns, NULs and vertical tabs at both ends. */
    public static String phpTrim(String text) {
        Objects.requireNonNull(text, "text");
        int start = 0;
        int end = text.length();
        while (start < end && Chars.isPhpTrim(text.charAt(start))) {
            start++;
        }
        while (end > start && Chars.isPhpTrim(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }
}
