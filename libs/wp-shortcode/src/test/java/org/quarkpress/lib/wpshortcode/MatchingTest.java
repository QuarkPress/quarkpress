package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MatchingTest {

    // the echo registry plus an emoji tag, as when the expected values were produced
    private static final Shortcodes SHORTCODES = Shortcodes.builder(Registries.echo().toBuilder()
                    .add("\uD83D\uDE00", Echo.HANDLER)
                    .build())
            .build();

    private static void assertApply(String content, String expected) {
        assertThat(SHORTCODES.apply(content)).as(content).isEqualTo(expected);
    }

    private static void assertApply(ShortcodeRegistry registry, String content, String expected) {
        assertThat(Shortcodes.builder(registry).build().apply(content))
                .as(content)
                .isEqualTo(expected);
    }

    @Test
    void earlyExits() { // R2.1, R2.2
        assertApply("&#91;foo&#93;", "&#91;foo&#93;");
        assertApply("plain text", "plain text");
        String text = "[foo]";
        assertThat(Shortcodes.builder(ShortcodeRegistry.builder().build())
                        .build()
                        .apply(text))
                .isSameAs(text);
    }

    @Test
    void candidatePrefilter() { // R2.3
        assertApply("[foo.x]", "[foo.x]");
        assertApply("[foo.x] [foo]", "{foo:[\".x\"]:\"\"} {foo:[]:\"\"}");
        assertApply("[foo:x] [foo]", "{foo:[\":x\"]:\"\"} {foo:[]:\"\"}");
        assertApply("[foo!] [foo]", "{foo:[\"!\"]:\"\"} {foo:[]:\"\"}");
        assertApply("[foo a=1]", "{foo:{\"a\":\"1\"}:\"\"}");
        assertApply("[foo a=1] [foo]", "{foo:{\"a\":\"1\"}:\"\"} {foo:[]:\"\"}");
        assertApply("[foo=1]", "{foo:[\"=1\"]:\"\"}");
        assertApply("[foo&x]", "{foo:[\"&x\"]:\"\"}");
        assertApply("[foo\u00a0x] [foo]", "{foo:[\"x\"]:\"\"} {foo:[]:\"\"}");
        assertApply("[/foo]", "[/foo]");
        assertApply("[foo,] [foo,]", "[foo,] [foo,]");
    }

    @Test
    void nameBoundary() { // R3.3
        assertApply("[foo-bar] [foo]", "{foo-bar:[]:\"\"} {foo:[]:\"\"}");
        assertApply("[foobar] [foo]", "[foobar] {foo:[]:\"\"}");
        assertApply("[foo_] [foo_bar]", "[foo_] {foo_bar:[]:\"\"}");
        assertApply("[FOO]", "[FOO]");
        assertApply("[\u00fc] [\u00fcx]", "{\u00fc:[]:\"\"} [\u00fcx]");
        assertApply("[x.y]", "{x.y:[]:\"\"}");
    }

    @Test
    void registryOrderDecidesAmbiguousNames() { // R3.3a
        ShortcodeRegistry xFirst = ShortcodeRegistry.builder()
                .add("x", Echo.HANDLER)
                .add("x.y", Echo.HANDLER)
                .build();
        ShortcodeRegistry xyFirst = ShortcodeRegistry.builder()
                .add("x.y", Echo.HANDLER)
                .add("x", Echo.HANDLER)
                .build();
        assertApply(xFirst, "[x.y] [x]", "{x:[\".y\"]:\"\"} {x:[]:\"\"}");
        assertApply(xFirst, "[x.y]", "{x.y:[]:\"\"}");
        assertApply(xyFirst, "[x.y] [x]", "{x.y:[]:\"\"} {x:[]:\"\"}");
        assertApply(xyFirst, "[x.y]", "{x.y:[]:\"\"}");
    }

    @Test
    void attributeText() { // R3.4
        assertApply("[foo/]", "{foo:[]:\"\"}");
        assertApply("[foo /]", "{foo:[]:\"\"}");
        assertApply("[foo a=\"1\"/]", "{foo:{\"a\":\"1\"}:\"\"}");
        assertApply("[foo a=1/]", "{foo:{\"a\":\"1\"}:\"\"}");
        assertApply("[foo /a]", "{foo:[\"/a\"]:\"\"}");
        assertApply("[foo/ ]", "{foo:[\"/\"]:\"\"}");
        assertApply("[foo a=\"x/y\"]", "{foo:{\"a\":\"x/y\"}:\"\"}");
        assertApply("[foo url=http://x.com/]", "{foo:{\"url\":\"http://x.com\"}:\"\"}");
        assertApply("[foo a=\"x]y\"]", "{foo:[\"a=\\\"x\"]:\"\"}y\"]");
        assertApply("[foo\ta=1]", "{foo:{\"a\":\"1\"}:\"\"}");
        assertApply("[foo\na=1]", "{foo:{\"a\":\"1\"}:\"\"}");
        assertApply("[ foo]", "[ foo]");
        assertApply("[foo//]", "{foo:[\"/\"]:\"\"}");
    }

    @Test
    void enclosing() { // R3.5
        assertApply("[foo]x[/foo]", "{foo:[]:\"x\"}");
        assertApply("[foo][/foo]", "{foo:[]:\"\"}");
        assertApply("[foo]x[/foo] [foo]y[/foo]", "{foo:[]:\"x\"} {foo:[]:\"y\"}");
        assertApply("[foo] text [foo]x[/foo]", "{foo:[]:\" text [foo]x\"}");
        assertApply("[foo a]x[/foo][foo b]y[/foo]", "{foo:[\"a\"]:\"x\"}{foo:[\"b\"]:\"y\"}");
        assertApply("[foo]\n\n[/foo]", "{foo:[]:\"\\n\\n\"}");
        assertApply("[foo]a[[/foo]", "{foo:[]:\"a[\"}");
    }

    @Test
    void selfClosingNeverEncloses() { // R3.6
        assertApply("[foo /]x[/foo]", "{foo:[]:\"\"}x[/foo]");
        assertApply("[foo/]x[/foo]", "{foo:[]:\"\"}x[/foo]");
    }

    @Test
    void sameTagDoesNotNest() { // R3.7
        assertApply("[foo]a[foo]b[/foo]c[/foo]", "{foo:[]:\"a[foo]b\"}c[/foo]");
        assertApply("[foo][foo][/foo]", "{foo:[]:\"[foo]\"}");
        assertApply("[foo]a[/foo][/foo]", "{foo:[]:\"a\"}[/foo]");
    }

    @Test
    void differentTagsNestAsRawContent() { // R3.8
        assertApply("[b][foo]x[/foo][/b]", "{b:[]:\"[foo]x[/foo]\"}");
        assertApply("[foo]\n[foo/]\n[/foo]", "{foo:[]:\"\\n[foo/]\\n\"}");
    }

    @Test
    void improperClosers() { // R3.9
        assertApply("[foo]x[/FOO]", "{foo:[]:\"\"}x[/FOO]");
        assertApply("[foo a=1]x[/foo ]", "{foo:{\"a\":\"1\"}:\"\"}x[/foo ]");
        assertApply("[foo]x[ /foo]", "{foo:[]:\"\"}x[ /foo]");
        assertApply("[foo]x[/foo", "{foo:[]:\"\"}x[/foo");
        assertApply("[foo]x[/b]", "{foo:[]:\"\"}x[/b]");
        assertApply("[foo]a[b]b[/foo]c[/b]", "{foo:[]:\"a[b]b\"}c[/b]");
        assertApply("[b]x[foo]y[/b]", "{b:[]:\"x[foo]y\"}");
    }

    @Test
    void unclosedOpeners() { // R3.10
        assertApply("[foo", "[foo");
        assertApply("[foo a=\"1\"", "[foo a=\"1\"");
        assertApply("[[[foo", "[[[foo");
    }

    @Test
    void registryRulesShowInOutput() { // R1.1, R1.2, R1.3
        ShortcodeRegistry dup = ShortcodeRegistry.builder()
                .add("dup", (s, c) -> "one")
                .add("dup", (s, c) -> "two")
                .add("z", (s, c) -> "Z")
                .build();
        assertApply(dup, "[dup][z]", "twoZ");
        assertApply(dup.toBuilder().remove("dup").build(), "[dup]", "[dup]");
        ShortcodeRegistry.Builder odd = ShortcodeRegistry.builder();
        for (String name : new String[] {"a\"b", "a'b", "a|b", "a{b}", "-x", "a.b", "a\u007f"}) {
            odd.add(name, Echo.HANDLER);
        }
        assertApply(
                odd.build(),
                "[a\"b] [a'b x] [a|b] [a{b}] [-x] [a.b]",
                "{a\"b:[]:\"\"} {a'b:[\"x\"]:\"\"} {a|b:[]:\"\"} {a{b}:[]:\"\"} {-x:[]:\"\"} {a.b:[]:\"\"}");
        assertApply(odd.build(), "[a\u007f]", "{a\u007f:[]:\"\"}");
        assertApply("[FOO]", "[FOO]");
    }

    @Test
    void supplementaryCharacters() { // review focus 1
        assertApply(
                "[\ud83d\ude00 a=\"\ud83c\udf89\" \ud83c\udf88]x\ud83c\udf81[/\ud83d\ude00]",
                "{\ud83d\ude00:{\"a\":\"\ud83c\udf89\",\"0\":\"\ud83c\udf88\"}:\"x\ud83c\udf81\"}");
        assertApply("[foo\ud83d\ude00] [foo]", "{foo:[\"\ud83d\ude00\"]:\"\"} {foo:[]:\"\"}");
        assertApply(
                "[foo \ud83d\ude00=1 b=\ud83d\ude00]", "{foo:{\"0\":\"\ud83d\ude00=1\",\"b\":\"\ud83d\ude00\"}:\"\"}");
    }

    @Test
    void windowsLineEndings() { // review focus 4
        assertApply("[foo a=1\r\nb=2]x\r\n[/foo]", "{foo:{\"a\":\"1\",\"b\":\"2\"}:\"x\\r\\n\"}");
        assertApply("<p>[foo]\r\n</p>", "<p>{foo:[]:\"\"}\r\n</p>");
        assertApply("[foo]\r\n[/foo]\r\n", "{foo:[]:\"\\r\\n\"}\r\n");
    }
}
