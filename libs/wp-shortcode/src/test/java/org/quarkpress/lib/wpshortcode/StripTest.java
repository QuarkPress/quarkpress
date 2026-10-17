package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class StripTest {

    private static final ShortcodeRegistry REGISTRY =
            Registries.constant("C", "gallery", "outer", "inner", "raw", "x.y");
    private static final Shortcodes SHORTCODES = Shortcodes.builder(REGISTRY).build();

    private static void assertStrip(String content, String expected) {
        assertThat(SHORTCODES.strip(content)).as(content).isEqualTo(expected);
    }

    @Test
    void stripsLikeWordPress() { // R11.1, R11.3, R11.4, R11.5
        assertStrip(
                "a [gallery] b [outer]x[inner]y[/outer] c [[gallery]] [nope] <a title=\"[gallery]\">t</a> [gallery]]",
                "a  b  c [gallery] [nope] <a title=\"[gallery]\">t</a> ]");
        assertStrip("<!-- [gallery] -->", "<!-- [gallery] -->");
        assertStrip("[gallery] &#91;x&#93;", " &#091;x&#093;");
        assertStrip("&#91;x&#93; [nope]", "&#91;x&#93; [nope]");
        assertStrip("[[gallery]x[/gallery]]", "[gallery]x[/gallery]");
        assertStrip("x <b>[gallery]</b>", "x <b></b>");
        assertStrip("<![CDATA[[gallery]]]>[inner]", "<![CDATA[[gallery]]]>");
    }

    @Test
    void theStripTagsHookChoosesTheNames() { // R11.2, R8.4
        Shortcodes onlyGallery = Shortcodes.builder(REGISTRY)
                .hooks(ShortcodeHooks.builder()
                        .stripTags((tags, content) -> List.of("gallery"))
                        .build())
                .build();
        assertThat(onlyGallery.strip("[gallery] [inner] [outer]x[/outer]")).isEqualTo(" [inner] [outer]x[/outer]");

        Shortcodes unregistered = Shortcodes.builder(REGISTRY)
                .hooks(ShortcodeHooks.builder()
                        .stripTags((tags, content) -> List.of("unreg", "inner"))
                        .build())
                .build();
        assertThat(unregistered.strip("[unreg]x[/unreg] [inner] [gallery]")).isEqualTo("  [gallery]");
    }

    @Test
    void stripCallsNoHandler() {
        Shortcodes s = Shortcodes.builder(ShortcodeRegistry.builder()
                        .add("t", (sc, c) -> {
                            throw new AssertionError("handler called");
                        })
                        .build())
                .build();
        assertThat(s.strip("a[t]b[t]x[/t]c")).isEqualTo("ac");
    }
}
