package org.quarkword.lib.wpformatting;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.quarkword.testing.gen.Gen;
import org.quarkword.testing.gen.Gens;

/**
 * Generators of formatting-heavy text for the generated parity cases: HTML-ish fragments that hit the block, pre,
 * script, comment, shortcode, quote, dash, entity, smiley, capital P, whitespace and footnote rules of every
 * formatter. Every fragment is whole UTF-16 (an emoji is one fragment), so no value holds a lone surrogate, which JSON
 * could not carry to PHP.
 */
final class FormattingArbitraries {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** The footnote marker the block editor writes; generated inputs also hold it cut short. */
    static final String FOOTNOTE = "<sup data-fn=\"f\" class=\"fn\"><a href=\"#f\" id=\"f-link\">1</a></sup>";

    private static final List<String> TAGS = List.of(
            "<div>",
            "</div>",
            "<p>",
            "</p>",
            "<li>",
            "<ul>",
            "<blockquote cite=\"x>y\">",
            "<pre>",
            "</pre>",
            "<PRE>",
            "<preview>",
            "<script>",
            "</script>",
            "<style>",
            "<svg>",
            "<math>",
            "<br>",
            "<br />",
            "<hr>",
            "<option>",
            "<object>",
            "</object>",
            "<param>",
            "<source>",
            "<track>",
            "<figcaption>",
            "<code>",
            "</code>",
            "<code />",
            "<kbd>",
            "<tt>",
            "<textarea>",
            "<b>",
            "</b>",
            "<a href=\"?a=1&b=2\">",
            // closers of the newline-preserving, ignore-stack and no-texturize elements, and of common containers
            "</style>",
            "</svg>",
            "</math>",
            "</textarea>",
            "</kbd>",
            "</tt>",
            "</blockquote>",
            "</li>",
            "</ul>",
            "</a>",
            "</option>",
            "</figcaption>",
            // attributed tags, some with ">" and quotes inside the attribute value
            "<p class=\"x\">",
            "<div id='a'>",
            "<pre class=\"x\">",
            "<style media=\"x\">",
            "<p title=\"a>b\">",
            "<div data-x='\"q\"'>",
            "<pre title='it\"s>'>",
            "<code title=\"a'b\">",
            "<script type=\"t\" data-x=\">\">",
            "<textarea name=\"a>b\">",
            // custom elements
            "<my-el>",
            "</my-el>",
            "<x-y-z a=\"1\">",
            "</x-y-z>");

    /** Whole elements whose content autop, texturize and smilies must leave alone, with newlines and quotes inside. */
    private static final List<String> ELEMENTS = List.of(
            "<style>\na {b:c}\n\n</style>",
            "<style media=\"x\">\n\"a\" :)\n</style>",
            "<svg>\n<g>\n\n</g>\n</svg>",
            "<math>\n<mi>x</mi>\n\n</math>",
            "<textarea>\n'a' -- :)\n\n</textarea>",
            "<kbd>\"a\" -- 'b'</kbd>",
            "<tt>'tis ... (tm)</tt>",
            "<my-el>\n\"q\"\n\n</my-el>",
            "<x-y-z a=\"1\">\n:)</x-y-z>");

    private static final List<String> MARKUP = List.of("<!--", "-->", "<![CDATA[", "]]>", "<", ">");

    private static final List<String> MARKERS =
            List.of("<pre wp-pre-tag-0></pre>", " <!-- wpnl --> ", "<WPPreserveNewline />");

    private static final List<String> SHORTCODES =
            List.of("[caption]", "[/caption]", "[code]", "[/code]", "[[code]]", "[gallery ids=\"1\"]", "[foo]");

    private static final List<String> TEXTURIZE =
            List.of("\"", "'", "''", "``", "--", "---", "-", "...", " (tm)", "'99", "9", "9x9", "0x1", "xn--", "'tis");

    private static final List<String> ENTITIES =
            List.of("&", "&amp;", "&#038;", "&#x26;", "&nbsp;", "&abcdefghi;", "&frac58;", "&lt;", "&gt;", "&hellip;");

    private static final List<String> SMILIES = List.of(":)", ":-D", ":mrgreen:", "8)", ";-)", ":???:");

    private static final List<String> TEXT =
            List.of("Wordpress", " Wordpress", "(Wordpress", ">Wordpress", "word", "x", "é", "😀", "这是", "“");

    private static final List<String> WHITESPACE =
            List.of(" ", "\n", "\n\n", "\r\n", "\r", "\t", "\u000b", "\f", " ", " ", "\u0085", "\u0000");

    private FormattingArbitraries() {}

    /** The footnote marker, whole or cut after a random number of characters. */
    private static Gen<String> footnotes() {
        return Gens.oneOf(
                Gens.constant(FOOTNOTE), Gens.ints(1, FOOTNOTE.length() - 1).map(n -> FOOTNOTE.substring(0, n)));
    }

    private static Gen<String> join(Gen<String> fragment, int maxFragments) {
        return Gens.lists(fragment, 0, maxFragments).map(parts -> String.join("", parts));
    }

    /** HTML-ish text for every formatter: up to 40 fragments of tags, markup, markers, shortcodes, text and so on. */
    static Gen<String> html() {
        Gen<String> fragment = Gens.frequency(
                Gens.weighted(9, Gens.elements(TAGS)),
                Gens.weighted(2, Gens.elements(MARKUP)),
                Gens.weighted(2, Gens.elements(ELEMENTS)),
                Gens.weighted(1, Gens.elements(MARKERS)),
                Gens.weighted(2, Gens.elements(SHORTCODES)),
                Gens.weighted(5, Gens.elements(TEXTURIZE)),
                Gens.weighted(3, Gens.elements(ENTITIES)),
                Gens.weighted(2, Gens.elements(SMILIES)),
                Gens.weighted(5, Gens.elements(TEXT)),
                Gens.weighted(5, Gens.elements(WHITESPACE)),
                Gens.weighted(1, footnotes()));
        return join(fragment, 40);
    }

    /** Text for wp_trim_words and wp_html_excerpt: words, whitespace, tags, entities, CJK and emoji. */
    static Gen<String> words() {
        Gen<String> fragment = Gens.frequency(
                Gens.weighted(8, Gens.elements("word", "x", "Wordpress", "a.b", "été", "😀")),
                Gens.weighted(4, Gens.elements("这是", "中", "文字", "、", "。")),
                Gens.weighted(8, Gens.elements(WHITESPACE)),
                Gens.weighted(3, Gens.elements("<b>", "</b>", "<p>", "</p>", "<br />", "<script>x</script>", "<")),
                Gens.weighted(2, Gens.elements(ENTITIES)),
                Gens.weighted(1, Gens.elements("<style>a b</style>", "<!-- c -->", "&#8230;", "&#x1F600;")));
        return join(fragment, 60);
    }

    /** wp_trim_words arguments: {@code num}, {@code more}, {@code type}, {@code charset}. */
    static Gen<ObjectNode> trimArgs() {
        Gen<Integer> num = Gens.frequency(
                Gens.weighted(30, Gens.ints(-3, 60)),
                Gens.weighted(1, Gens.elements(Integer.MIN_VALUE, Integer.MAX_VALUE)));
        Gen<String> more = Gens.elements("absent", " [&hellip;]", "");
        Gen<String> type = Gens.elements("words", "characters_excluding_spaces", "characters_including_spaces");
        Gen<String> charset = Gens.elements("UTF-8", "utf8", "ISO-8859-1");
        return random -> {
            ObjectNode args = NODES.objectNode();
            args.put("num", num.next(random));
            putMore(args, more.next(random));
            args.put("type", type.next(random));
            args.put("charset", charset.next(random));
            return args;
        };
    }

    /** wp_html_excerpt arguments: {@code count} and {@code more}. */
    static Gen<ObjectNode> excerptArgs() {
        Gen<Integer> count = Gens.ints(-3, 40);
        Gen<String> more = Gens.elements("absent", "…", "!");
        return random -> {
            ObjectNode args = NODES.objectNode();
            args.put("count", count.next(random));
            putMore(args, more.next(random));
            return args;
        };
    }

    /**
     * wp_trim_excerpt arguments: the raw excerpt ({@code raw}, mostly empty), {@code length}, {@code more},
     * {@code type} and {@code charset}.
     */
    static Gen<ObjectNode> trimExcerptArgs() {
        Gen<String> raw = Gens.frequency(
                Gens.weighted(8, Gens.constant("")), Gens.weighted(1, Gens.elements(" \t\n", "\f", "Given excerpt")));
        Gen<Integer> length = Gens.frequency(
                Gens.weighted(30, Gens.ints(-3, 60)),
                Gens.weighted(1, Gens.elements(Integer.MIN_VALUE, Integer.MAX_VALUE)));
        Gen<String> more = Gens.elements("absent", "&hellip;", "");
        Gen<String> type = Gens.elements("words", "characters_excluding_spaces", "characters_including_spaces");
        Gen<String> charset = Gens.elements("UTF-8", "ISO-8859-1");
        return random -> {
            ObjectNode args = NODES.objectNode();
            args.put("raw", raw.next(random));
            args.put("length", length.next(random));
            putMore(args, more.next(random));
            args.put("type", type.next(random));
            args.put("charset", charset.next(random));
            return args;
        };
    }

    private static void putMore(ObjectNode args, String more) {
        if (!more.equals("absent")) {
            args.put("more", more);
        }
    }
}
