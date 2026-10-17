package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultsTest {

    private static Map<String, String> ordered(String... keysAndValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** Renders {@code content}; each [gal] records its merged attributes as text, which shows the key order. */
    private static List<String> render(String content, Map<String, String> defaults, String tagArgument) {
        List<String> seen = new ArrayList<>();
        ShortcodeHooks hooks = ShortcodeHooks.builder()
                .attributeDefaults("gal", (out, d, atts, tag) -> {
                    Map<String, String> copy = new LinkedHashMap<>(out);
                    copy.put("extra", "E");
                    return copy;
                })
                .attributeDefaults("0", (out, d, atts, tag) -> Map.of("zero", "Z"))
                .build();
        ShortcodeRegistry registry = ShortcodeRegistry.builder()
                .add("gal", (s, c) -> {
                    seen.add(s.attributes()
                            .withDefaults(defaults, tagArgument == null ? s.tag() : tagArgument)
                            .toString());
                    return "";
                })
                .build();
        Shortcodes.builder(registry).hooks(hooks).build().apply(content);
        return seen;
    }

    @Test
    void handlersMergeDefaultsAndRunTheTagsHooks() { // R9.1, R9.3
        assertThat(render("[gal a=x c=y z=q pos]", ordered("a", "1", "b", "2", "C", "3"), null))
                .containsExactly("{a=x, b=2, C=3, extra=E}");
        assertThat(render("[gal p 5=five]", ordered("a", "1", "0", "d", "5", "f"), null))
                .containsExactly("{a=1, 0=p, 5=five, extra=E}");
    }

    @Test
    void emptyAndZeroTagsSkipTheHooks() { // R9.3: "0" is falsy in PHP
        assertThat(render("[gal]", ordered("a", "1"), "0")).containsExactly("{a=1}");
        assertThat(render("[gal]", ordered("a", "1"), "")).containsExactly("{a=1}");
    }

    @Test
    void staticallyParsedAttributesKnowNoHooks() {
        assertThat(ShortcodeAttributes.parse("a=x").withDefaults(ordered("a", "1"), "gal"))
                .isEqualTo(ordered("a", "x"));
    }
}
