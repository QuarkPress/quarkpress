package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class HooksTest {

    private static String apply(ShortcodeHooks hooks, String content) {
        return Shortcodes.builder(Registries.callbacks()).hooks(hooks).build().apply(content);
    }

    @Test
    void preRenderReplacesTheWholeRendering() { // R8.1
        ShortcodeHooks pre = ShortcodeHooks.builder()
                .preRender((s, current) -> s.tag().equals("inner") ? Optional.of("PRE") : current)
                .build();
        assertThat(apply(pre, "[[inner]] [inner a=1] [outer]x[/outer]")).isEqualTo("[inner] PRE <o>x</o>");
        assertThat(apply(pre, "[inner]] [[inner]")).isEqualTo("PRE PRE");

        ShortcodeHooks empty = ShortcodeHooks.builder()
                .preRender((s, current) -> s.tag().equals("outer") ? Optional.of("") : current)
                .build();
        assertThat(apply(empty, "[outer]x[/outer]|[inner]")).isEqualTo("|I");

        ShortcodeHooks chained = ShortcodeHooks.builder()
                .preRender((s, current) -> current.isPresent() ? current : Optional.of("A"))
                .preRender(5, (s, current) -> Optional.of(current.orElse("") + "B"))
                .build();
        assertThat(apply(chained, "[inner]")).isEqualTo("B");
    }

    @Test
    void postRenderSeesTheEscapeBrackets() { // R8.2
        ShortcodeHooks post = ShortcodeHooks.builder()
                .postRender((out, s) -> "<" + s.tag() + ":" + out + ">")
                .build();
        assertThat(apply(post, "[inner a=\"1\"]x[/inner] [[inner]] [inner]]"))
                .isEqualTo("<inner:I> [inner] <inner:I]>");
        assertThat(apply(post, "[[inner]")).isEqualTo("<inner:[I>");

        ShortcodeHooks ordered = ShortcodeHooks.builder()
                .postRender((out, s) -> "<" + s.tag() + ":" + out + ">")
                .postRender(5, (out, s) -> "(" + out + ")")
                .build();
        assertThat(apply(ordered, "[inner]")).isEqualTo("<inner:(I)>");
    }
}
