package org.quarkpress.lib.wpshortcode;

import java.util.List;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;

/**
 * Generators of shortcode-heavy text. The fragments never form a {@code style} attribute or an {@code object}
 * element (deliberate differences D4 and D9) and never a positional attribute after integer key
 * 9223372036854775807 (D8), so generated parity cases can only differ from WordPress by a bug.
 */
final class ShortcodeArbitraries {

    /** The tags of the parity driver's "main" profile, in its registration order. */
    static final List<String> MAIN_TAGS = List.of(
            "foo", "b", "foo-bar", "foo_bar", "x.y", "ü", "gallery", "inner", "outer", "quiet", "raw", "emit", "ent",
            "nul", "cls", "url", "js", "attr", "amp");

    private static final List<String> TAGS = List.of(
            "[foo]",
            "[/foo]",
            "[b]",
            "[/b]",
            "[inner]",
            "[outer]",
            "[/outer]",
            "[quiet]",
            "[/quiet]",
            "[raw]",
            "[/raw]",
            "[cls]",
            "[url]",
            "[js]",
            "[attr]",
            "[amp]",
            "[emit]",
            "[ent]",
            "[nul]",
            "[gallery]",
            "[/gallery]",
            "[x.y]",
            "[ü]",
            "[foo-bar]",
            "[foo_bar]",
            "[foo.x]",
            "[FOO]",
            "[nope]",
            "[/nope]",
            "[[foo]]",
            "[[inner]",
            "[foo]]",
            "[foo/]",
            "[foo /]",
            "[/foo ]",
            "[ foo]");

    private static final List<String> MARKUP = List.of(
            "<p>",
            "</p>",
            "<b>",
            "</b>",
            "<a href=\"",
            "<a title='",
            "<img alt=\"",
            "<p class=\"",
            "<span ",
            "\">",
            "'>",
            "/>",
            ">",
            "<",
            "<!--",
            "-->",
            "<![CDATA[",
            "]]>",
            "<[foo]>",
            "<a href=[url]>",
            "<p title=\"[cls]\">");

    private static final List<String> TEXT = List.of(
            "[", "]", "/", "=", "\"", "'", " ", "  ", "\n", "\r\n", "\t", "\u00a0", "&nbsp;", "&#91;", "&#93;",
            "&#091;", "&amp;", "&", "x", "text", "0", "é", "😀");

    private static final List<String> PLAIN_TEXT =
            TEXT.stream().filter(t -> t.indexOf('&') < 0 && t.indexOf('<') < 0).toList();

    private ShortcodeArbitraries() {}

    /** Attribute text in every token form, with odd names, escapes, invisible spaces and unclosed tags. */
    static Gen<String> attributeText() {
        Gen<String> name = Gens.elements("a", "A", "data-x", "a_b", "-", "0", "00", "5", "-1", "x1", "ü", "id");
        Gen<String> quoted = Gens.elements(
                "",
                "1",
                "x y",
                "it's",
                "say \"hi\"",
                "<b>x</b>",
                "<b",
                "x>y",
                "\\n",
                "\\x41",
                "\\101",
                "\\q",
                "\\\\",
                "a\\",
                "[x]",
                "/",
                "\u00a0",
                "é");
        Gen<String> bare = Gens.elements("1", "x", "<b>", "<b", "\\t", "\\x4", "a=b", "http://x.com/", "é");
        Gen<String> doubleQuoted = random -> name.next(random) + "=\"" + quoted.next(random) + "\"";
        Gen<String> singleQuoted = random -> name.next(random) + "='" + quoted.next(random) + "'";
        Gen<String> unquoted = random -> name.next(random) + "=" + bare.next(random);
        Gen<String> spaced = random -> name.next(random) + " = \"" + quoted.next(random) + "\"";
        Gen<String> positionalDouble = random -> "\"" + quoted.next(random) + "\"";
        Gen<String> positionalSingle = random -> "'" + quoted.next(random) + "'";
        Gen<String> token = Gens.oneOf(
                doubleQuoted, singleQuoted, unquoted, spaced, positionalDouble, positionalSingle, bare, name);
        Gen<String> separator = Gens.elements(" ", " ", "  ", "\t", "\n", "\u000b", "\f", "\u00a0", "\u200b", "");
        Gen<List<String>> tokens = Gens.lists(token, 0, 6);
        return random -> {
            StringBuilder out = new StringBuilder();
            for (String t : tokens.next(random)) {
                out.append(separator.next(random)).append(t);
            }
            return out.toString();
        };
    }

    /** Text that mixes tags, openers with attributes, HTML, comments, entities and stray brackets. */
    static Gen<String> content() {
        Gen<String> attributes = attributeText();
        Gen<String> openerName = Gens.elements("foo", "b", "outer", "gallery", "x.y", "inner");
        Gen<String> openerEnd = Gens.elements("]", "/]", "]]");
        Gen<String> opener = random -> "[" + openerName.next(random) + attributes.next(random) + openerEnd.next(random);
        Gen<String> fragment = Gens.frequency(
                Gens.weighted(6, Gens.elements(TAGS)),
                Gens.weighted(3, opener),
                Gens.weighted(3, Gens.elements(MARKUP)),
                Gens.weighted(4, Gens.elements(TEXT)));
        return Gens.lists(fragment, 0, 30).map(parts -> String.join("", parts));
    }

    /** Text without '<' and '&', which the HTML pass and the entity steps leave alone. */
    static Gen<String> plainContent() {
        Gen<String> fragment =
                Gens.frequency(Gens.weighted(6, Gens.elements(TAGS)), Gens.weighted(4, Gens.elements(PLAIN_TEXT)));
        return Gens.lists(fragment, 0, 30).map(parts -> String.join("", parts));
    }
}
