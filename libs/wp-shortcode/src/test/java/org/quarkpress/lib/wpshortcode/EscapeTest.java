package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EscapeTest {

    private static final Shortcodes SHORTCODES =
            Shortcodes.builder(Registries.echo()).build();

    private static void assertApply(String content, String expected) {
        assertThat(SHORTCODES.apply(content)).as(content).isEqualTo(expected);
    }

    @Test
    void doubleBracketsEscapeAndOneSidedBracketsStay() { // R5.1, R5.2
        assertApply("[[foo]]", "[foo]");
        assertApply("[[foo]x[/foo]]", "[foo]x[/foo]");
        assertApply("[[[foo]]]", "[[foo]]");
        assertApply("[[foo]", "[{foo:[]:\"\"}");
        assertApply("[foo]]", "{foo:[]:\"\"}]");
        assertApply("[[foo]x[/foo]", "[{foo:[]:\"x\"}");
        assertApply("[foo]x[/foo]]", "{foo:[]:\"x\"}]");
        assertApply("[[foo a=\"1\"/]]", "[foo a=\"1\"/]");
    }

    @Test
    void hooksSeeWordPresssMatchParts() { // \u00a74, as do_shortcode_tag receives them
        List<List<String>> seen = new ArrayList<>();
        Shortcodes shortcodes = Shortcodes.builder(Registries.callbacks())
                .hooks(ShortcodeHooks.builder()
                        .postRender((out, s) -> {
                            seen.add(s.match().parts());
                            return out;
                        })
                        .build())
                .build();
        shortcodes.apply("[inner a=\"1\"]x[/inner]");
        shortcodes.apply("[inner]]");
        shortcodes.apply("[[inner /]");
        shortcodes.apply("[inner]");
        assertThat(seen)
                .containsExactly(
                        List.of("[inner a=\"1\"]x[/inner]", "", "inner", " a=\"1\"", "", "x", ""),
                        List.of("[inner]]", "", "inner", "", "", "", "]"),
                        List.of("[[inner /]", "[", "inner", " ", "/", "", ""),
                        List.of("[inner]", "", "inner", "", "", "", ""));
    }

    @Test
    void escapedShortcodesCallNothing() { // R5.1
        List<String> calls = new ArrayList<>();
        Shortcodes shortcodes = Shortcodes.builder(ShortcodeRegistry.builder()
                        .add("t", (s, c) -> {
                            calls.add("handler");
                            return "T";
                        })
                        .build())
                .hooks(ShortcodeHooks.builder()
                        .preRender((s, current) -> {
                            calls.add("pre");
                            return current;
                        })
                        .postRender((out, s) -> {
                            calls.add("post");
                            return out;
                        })
                        .build())
                .build();
        assertThat(shortcodes.apply("[[t a=1]x[/t]]")).isEqualTo("[t a=1]x[/t]");
        assertThat(calls).isEmpty();
    }
}
