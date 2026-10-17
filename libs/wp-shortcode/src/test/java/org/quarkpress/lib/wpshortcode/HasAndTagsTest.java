package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HasAndTagsTest {

    private final Shortcodes shortcodes = Shortcodes.builder(
                    Registries.constant("C", "gallery", "outer", "inner", "raw", "x.y"))
            .build();

    @Test
    void hasShortcode() { // R10.1
        assertThat(shortcodes.has("[[gallery]]", "gallery")).isEqualTo(true);
        assertThat(shortcodes.has("<a title=\"[gallery]\">", "gallery")).isEqualTo(true);
        assertThat(shortcodes.has("[outer][gallery][/outer]", "gallery")).isEqualTo(true);
        assertThat(shortcodes.has("[outer][raw][gallery][/raw][/outer]", "gallery"))
                .isEqualTo(true);
        assertThat(shortcodes.has("[nope]", "nope")).isEqualTo(false);
        assertThat(shortcodes.has("[x.y]", "x")).isEqualTo(false);
        assertThat(shortcodes.has("no brackets", "gallery")).isEqualTo(false);
        assertThat(shortcodes.has("[gallery", "gallery")).isEqualTo(false);
        assertThat(shortcodes.has("[outer]0[/outer]", "gallery")).isEqualTo(false);
        assertThat(shortcodes.has("[outer][inner]x[/outer]", "inner")).isEqualTo(true);
    }

    @Test
    void tagsInContent() { // R10.2
        assertThat(shortcodes.tagsIn("[outer][gallery][/outer] [[raw]] [nope] [inner]"))
                .containsExactly("outer", "gallery", "raw", "inner");
        assertThat(shortcodes.tagsIn("[outer][raw][gallery][/raw][inner][/outer][x.y]"))
                .containsExactly("outer", "raw", "gallery", "inner", "x.y");
        assertThat(shortcodes.tagsIn("none")).containsExactly();
        assertThat(shortcodes.tagsIn("[inner][inner]")).containsExactly("inner", "inner");
    }

    @Test
    void anEmptyRegistryHasNoTags() { // deliberate difference D7: WordPress reports "" for "[ x]"
        Shortcodes none =
                Shortcodes.builder(ShortcodeRegistry.builder().build()).build();
        assertThat(none.tagsIn("[foo] [ x]")).isEmpty();
        assertThat(none.has("[foo]", "foo")).isFalse();
    }

    @Test
    void deepNestingUsesNoRecursion() {
        ShortcodeRegistry.Builder b = ShortcodeRegistry.builder();
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            b.add("t" + i, (s, c) -> "");
            open.append("[t").append(i).append(']');
            close.insert(0, "[/t" + i + "]");
        }
        Shortcodes deep = Shortcodes.builder(b.build()).build();
        String content = open + "x" + close;
        assertThat(deep.tagsIn(content)).hasSize(2_000).startsWith("t0", "t1").endsWith("t1999");
        assertThat(deep.has(content, "t1999")).isTrue();
    }
}
