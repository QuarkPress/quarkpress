package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ShortcodeHooksTest {

    private static final ShortcodeMatch MATCH = new ShortcodeMatch("[t]", "", "t", "", "", "", "");
    private static final Shortcode SHORTCODE = new Shortcode("t", ShortcodeAttributes.empty(), "", false, false, MATCH);

    @Test
    void preRenderHooksRunByPriorityThenRegistrationAndChain() {
        List<String> calls = new ArrayList<>();
        ShortcodeHooks hooks = ShortcodeHooks.builder()
                .preRender((s, current) -> {
                    calls.add("10a:" + current.orElse("false"));
                    return current.isPresent() ? current : Optional.of("A");
                })
                .preRender(5, (s, current) -> {
                    calls.add("5:" + current.orElse("false"));
                    return Optional.of(current.orElse("") + "B");
                })
                .preRender(10, (s, current) -> {
                    calls.add("10b:" + current.orElse("false"));
                    return current;
                })
                .build();
        assertThat(hooks.preRender(SHORTCODE)).contains("B");
        assertThat(calls).containsExactly("5:false", "10a:B", "10b:B");
    }

    @Test
    void noPreRenderHooksMeansNotHandled() {
        assertThat(ShortcodeHooks.NONE.preRender(SHORTCODE)).isEmpty();
    }

    @Test
    void postRenderHooksChainInPriorityOrder() {
        ShortcodeHooks hooks = ShortcodeHooks.builder()
                .postRender((out, s) -> "<" + s.tag() + ":" + out + ">")
                .postRender(5, (out, s) -> "(" + out + ")")
                .build();
        assertThat(hooks.postRender("I", SHORTCODE)).isEqualTo("<t:(I)>");
    }

    @Test
    void attributeDefaultsHooksApplyToTheirTagOnly() {
        ShortcodeHooks hooks = ShortcodeHooks.builder()
                .attributeDefaults("gal", (out, defaults, atts, tag) -> {
                    Map<String, String> copy = new LinkedHashMap<>(out);
                    copy.put("extra", "E");
                    return copy;
                })
                .build();
        Map<String, String> out = Map.of("a", "x");
        assertThat(hooks.attributeDefaults("gal", out, Map.of(), ShortcodeAttributes.empty()))
                .containsEntry("extra", "E")
                .containsEntry("a", "x");
        assertThat(hooks.attributeDefaults("other", out, Map.of(), ShortcodeAttributes.empty()))
                .isSameAs(out);
    }

    @Test
    void stripTagsHooksReplaceTheList() {
        ShortcodeHooks hooks = ShortcodeHooks.builder()
                .stripTags((tags, content) -> List.of("gallery"))
                .build();
        assertThat(hooks.stripTags(List.of("a", "gallery"), "x")).containsExactly("gallery");
        assertThat(ShortcodeHooks.NONE.stripTags(List.of("a"), "x")).containsExactly("a");
    }

    @Test
    void nullResults() {
        ShortcodeHooks nullPost =
                ShortcodeHooks.builder().postRender((out, s) -> null).build();
        assertThat(nullPost.postRender("I", SHORTCODE)).isEmpty();

        ShortcodeHooks nullPre =
                ShortcodeHooks.builder().preRender((s, current) -> null).build();
        assertThatNullPointerException()
                .isThrownBy(() -> nullPre.preRender(SHORTCODE))
                .withMessageContaining("Optional.empty()");

        ShortcodeHooks nullDefaults = ShortcodeHooks.builder()
                .attributeDefaults("t", (out, defaults, atts, tag) -> null)
                .build();
        assertThatNullPointerException()
                .isThrownBy(() -> nullDefaults.attributeDefaults("t", Map.of(), Map.of(), ShortcodeAttributes.empty()))
                .withMessageContaining("AttributeDefaultsHook");

        assertThatNullPointerException()
                .isThrownBy(() -> ShortcodeHooks.builder().preRender(null));
    }

    @Test
    void matchPartsAreInWordPressOrder() { // behavior reference section 4
        ShortcodeMatch m = new ShortcodeMatch("[[t a/]", "[", "t", " a", "/", "", "");
        assertThat(m.parts()).containsExactly("[[t a/]", "[", "t", " a", "/", "", "");
        assertThat(m.escaped()).isFalse();
        assertThat(new ShortcodeMatch("[[t]]", "[", "t", "", "", "", "]").escaped())
                .isTrue();
    }
}
