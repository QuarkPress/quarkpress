package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UnautopTest {

    private static Shortcodes shortcodes(String... names) {
        ShortcodeRegistry.Builder b = ShortcodeRegistry.builder();
        for (String name : names) {
            b.add(name, Echo.HANDLER);
        }
        return Shortcodes.builder(b.build()).build();
    }

    private static final Shortcodes SHORTCODES = shortcodes("inner", "outer", "x", "x.y");

    private static void assertUnautop(String content, String expected) {
        assertThat(SHORTCODES.unautop(content)).as(content).isEqualTo(expected);
    }

    @Test
    void unwrapsShortcodesThatStandAloneInAParagraph() { // R14.2
        assertUnautop("<p>[inner]</p>", "[inner]");
        assertUnautop("<p>&nbsp;[inner]&nbsp;</p>", "[inner]");
        assertUnautop("<p>\u00a0\t[inner /]\r\n</p>", "[inner /]");
        assertUnautop("<p>[inner] text</p>", "<p>[inner] text</p>");
        assertUnautop("<p>[innerx]</p>", "<p>[innerx]</p>");
        assertUnautop("<p>[inner]</p>\n<p> [inner a=1]x[/inner] </p>", "[inner]</p>\n<p> [inner a=1]x[/inner]");
        assertUnautop("<p>[outer]x[/outer]</p>", "[outer]x[/outer]");
        assertUnautop("<p>[outer]x</p><p>[/outer]</p>", "[outer]x</p><p>[/outer]");
        assertUnautop("<p>[outer]a[/outer]b</p>", "<p>[outer]a[/outer]b</p>");
        assertUnautop("<p>[[inner]]</p>", "<p>[[inner]]</p>");
        assertUnautop("<p>[x.y]</p>", "[x.y]");
        assertUnautop("<p>[inner]</p><p>[outer]</p>", "[inner][outer]");
        assertUnautop("<p class=\"a\">[inner]</p>", "<p class=\"a\">[inner]</p>");
        assertUnautop("<P>[inner]</P>", "<P>[inner]</P>");
        assertUnautop("<p>[inner", "<p>[inner");
        assertUnautop("<p><p>[inner]</p>", "<p>[inner]");
        assertUnautop("no paragraphs [inner]", "no paragraphs [inner]");
        assertUnautop("<p>[outer]x[/outer] </p>\n<p>[outer]y</p>", "[outer]x[/outer]\n<p>[outer]y</p>");
        assertUnautop("<p>[inner/]</p>", "[inner/]");
        assertUnautop("<p>[inner ]] </p>", "<p>[inner ]] </p>");
    }

    @Test
    void windowsLineEndings() { // review focus 4; registry foo
        Shortcodes foo = shortcodes("foo");
        assertThat(foo.unautop("<p>[foo]\r\n</p>")).isEqualTo("[foo]");
        assertThat(foo.unautop("<p>\r\n[foo a=1]\r\n</p>\r\n")).isEqualTo("[foo a=1]\r\n");
    }

    @Test
    void anEmptyRegistryChangesNothing() {
        String text = "<p>[foo]</p>";
        assertThat(shortcodes().unautop(text)).isSameAs(text);
    }
}
