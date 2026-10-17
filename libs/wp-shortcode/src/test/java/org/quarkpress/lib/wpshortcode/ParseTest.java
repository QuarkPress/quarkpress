package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParseTest {

    private static final Shortcodes SHORTCODES =
            Shortcodes.builder(Registries.echo()).build();

    @Test
    void splitsTextMatchesAndEscapes() {
        List<ShortcodeNode> nodes = SHORTCODES.parse("a [foo x=1]y[/foo] [[b]] z");
        assertThat(nodes).hasSize(5);
        assertThat(nodes.get(0)).isEqualTo(new ShortcodeNode.Text("a "));
        Shortcode foo = ((ShortcodeNode.Match) nodes.get(1)).shortcode();
        assertThat(foo.tag()).isEqualTo("foo");
        assertThat(foo.content()).isEqualTo("y");
        assertThat(foo.enclosing()).isTrue();
        assertThat(foo.attributes().get("x")).contains("1");
        assertThat(nodes.get(2)).isEqualTo(new ShortcodeNode.Text(" "));
        assertThat(nodes.get(3)).isInstanceOf(ShortcodeNode.Escaped.class);
        assertThat(nodes.get(3).source()).isEqualTo("[[b]]");
        assertThat(nodes.get(4)).isEqualTo(new ShortcodeNode.Text(" z"));
    }

    @Test
    void contentIsNotParsed() { // R3.8
        List<ShortcodeNode> nodes = SHORTCODES.parse("[b][foo][/foo][/b]");
        assertThat(nodes).hasSize(1);
        assertThat(((ShortcodeNode.Match) nodes.get(0)).shortcode().content()).isEqualTo("[foo][/foo]");
    }

    @Test
    void hidesBracketsInHtmlAndCallsNoHandler() { // decision 10
        AtomicInteger calls = new AtomicInteger();
        Shortcodes counting = Shortcodes.builder(ShortcodeRegistry.builder()
                        .add("foo", (s, c) -> {
                            calls.incrementAndGet();
                            return "";
                        })
                        .build())
                .build();
        List<ShortcodeNode> nodes = counting.parse("<p title=\"[foo]\">[foo]</p>");
        assertThat(nodes).hasSize(3);
        assertThat(nodes.get(0)).isEqualTo(new ShortcodeNode.Text("<p title=\"&#91;foo&#93;\">"));
        assertThat(nodes.get(1)).isInstanceOf(ShortcodeNode.Match.class);
        assertThat(nodes.get(2)).isEqualTo(new ShortcodeNode.Text("</p>"));
        assertThat(calls).hasValue(0);
    }

    @Test
    void sourcesRebuildTheProtectedInput() {
        String content = "&#91;x&#93; [foo] <a href='[foo]'>";
        String guarded = SHORTCODES.protectForParse(content);
        assertThat(guarded).isEqualTo("&#091;x&#093; [foo] <a href='&#91;foo&#93;'>");
        StringBuilder joined = new StringBuilder();
        SHORTCODES.parse(content).forEach(node -> joined.append(node.source()));
        assertThat(joined.toString()).isEqualTo(guarded);
    }

    @Test
    void earlyExitsGiveTheContentAsText() {
        assertThat(SHORTCODES.parse("plain")).containsExactly(new ShortcodeNode.Text("plain"));
        assertThat(SHORTCODES.parse("[nope] &#91;")).containsExactly(new ShortcodeNode.Text("[nope] &#91;"));
        assertThat(SHORTCODES.parse("")).isEmpty();
    }
}
