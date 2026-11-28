package org.quarkword.lib.wpstyleengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PresetsTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static JsonNode text(String s) {
        return NODES.textNode(s);
    }

    /** SE15, G12, G13, G18 and G196: the substring test, the last {@code |}, kebab case. */
    @Test
    void slug() {
        assertThat(Presets.slug(text("var:preset|color|fooBar"), "color")).isEqualTo("foo-bar");
        assertThat(Presets.slug(text("var:preset|color|fooBar"), "colour")).isEmpty();
        assertThat(Presets.slug(text("var:preset|font-size|x-large"), "font-size"))
                .isEqualTo("x-large");
        assertThat(Presets.slug(text("var:preset|color|a|bigRed"), "color")).isEqualTo("big-red");
        assertThat(Presets.slug(text("junkvar:preset|color|red"), "color")).isEqualTo("red");
        assertThat(Presets.slug(text("var:preset|color|"), "color")).isEmpty();
        assertThat(Presets.slug(text("var:preset|color|0"), "color")).isEqualTo("0");
        assertThat(Presets.slug(text("var:preset|color|a.b"), "color")).isEqualTo("a-b");
        assertThat(Presets.slug(text("var:preset|color|10px"), "color")).isEqualTo("10-px");
        assertThat(Presets.slug(text("var:preset|color|\u00dcn\u00efcode"), "color"))
                .isEqualTo("\u00dcn\u00efcode");
        // the preset type must match exactly, and both must be text
        assertThat(Presets.slug(text("var:preset|spacing|50"), "color")).isEmpty();
        assertThat(Presets.slug(NODES.numberNode(5), "color")).isEmpty();
        assertThat(Presets.slug(text("var:preset|color|x"), null)).isEmpty();
        assertThat(Presets.slug(null, "color")).isEmpty();
    }

    /** SE17 and G197: the first type with a slug wins, and the slug is put in once. */
    @Test
    void cssVarValue() {
        Map<String, String> ab = new LinkedHashMap<>();
        ab.put("a", "--A--$slug");
        ab.put("b", "--B--$slug");
        assertThat(Presets.cssVarValue(text("var:preset|b|x"), ab)).isEqualTo("var(--B--x)");
        assertThat(Presets.cssVarValue(text("var:preset|c|x"), Map.of("a", "--A--$slug")))
                .isEmpty();
        assertThat(Presets.cssVarValue(text("var:preset|a|my$slug"), Map.of("a", "--A--$slug--$slug")))
                .isEqualTo("var(--A--my-slug--my-slug)");
        Map<String, String> color = Map.of("color", "--wp--preset--color--$slug");
        // a slug of "0" is valid here, although it gives no class name
        assertThat(Presets.cssVarValue(text("var:preset|color|0"), color)).isEqualTo("var(--wp--preset--color--0)");
        assertThat(Presets.cssVarValue(text("var:preset|color|a|bigRed"), color))
                .isEqualTo("var(--wp--preset--color--big-red)");
        assertThat(Presets.cssVarValue(text("junkvar:preset|color|red"), color))
                .isEqualTo("var(--wp--preset--color--red)");
        assertThat(Presets.cssVarValue(text("var:preset|color|"), color)).isEmpty();
        assertThat(Presets.cssVarValue(NODES.numberNode(1), color)).isEmpty();
        assertThat(Presets.cssVarValue(text("var:preset|color|x"), Map.of())).isEmpty();
    }
}
