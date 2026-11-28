package org.quarkword.lib.wpstyleengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkword.testing.wporacle.OracleJson;

class BlockStylesTest {

    private static JsonNode json(String s) {
        try {
            return OracleJson.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(s, e);
        }
    }

    private static List<RecordedCase> recorded(String prefix) {
        return RecordedCases.load().stream()
                .filter(c -> c.id().startsWith(prefix))
                .toList();
    }

    /** SE6, SE7: the 37 rows in table order. */
    @Test
    void definitionsTable() {
        List<Definition> all = Definitions.all();
        assertThat(all).hasSize(37);
        assertThat(new LinkedHashSet<>(all.stream().map(Definition::group).toList()))
                .containsExactly("background", "color", "border", "shadow", "dimensions", "spacing", "typography");
        assertThat(all.get(0).key()).isEqualTo("backgroundImage");
        assertThat(all.get(36).key()).isEqualTo("writingMode");
        Definition borderColor = Definitions.find("border", "color").orElseThrow();
        assertThat(borderColor.cssVars()).isEmpty();
        assertThat(borderColor.individualPattern()).isEqualTo("border-%s-color");
        assertThat(borderColor.classnames())
                .containsExactly(
                        new ClassnameRule("has-border-color", null),
                        new ClassnameRule("has-$slug-border-color", "color"));
        Definition shadow = Definitions.find("shadow", "shadow").orElseThrow();
        assertThat(shadow.path()).containsExactly("shadow");
        assertThat(shadow.cssVars()).containsExactly(Map.entry("shadow", "--wp--preset--shadow--$slug"));
        Definition top = Definitions.find("border", "top").orElseThrow();
        assertThat(top.defaultProperty()).isNull();
        assertThat(top.valueFunc()).isEqualTo(ValueFunc.INDIVIDUAL_SIDES);
        assertThat(top.path()).containsExactly("border", "top");
        assertThat(Definitions.find("background", "backgroundImage")
                        .orElseThrow()
                        .valueFunc())
                .isEqualTo(ValueFunc.URL_OR_VALUE);
        assertThat(Definitions.find("spacing", "blockGap")).isEmpty();
        assertThat(Definitions.find("border", "0")).isEmpty();
        assertThatThrownBy(() -> all.add(top)).isInstanceOf(UnsupportedOperationException.class);
    }

    /** Review Focus 2: JSON type shapes WordPress's editor never sends; outputs are WordPress's. */
    @Test
    void jsonTypeShapes() {
        List<RecordedCase> cases = recorded("rf2-types-");
        assertThat(cases).hasSize(12);
        assertThat(cases).allSatisfy(c -> {
            assertThat(c.out()).as("recorded %s", c.id()).isNotNull();
            assertThat(Functions.ours(c.fn(), c.in(), c.args())).as(c.id()).isEqualTo(c.out());
        });
        // raw non-text values reach the declarations and never the CSS
        StyleResult padding = StyleEngine.getStyles(json("{\"spacing\":{\"padding\":{\"top\":1.5,\"right\":true}}}"));
        assertThat(padding.css()).isEmpty();
        assertThat(padding.declarations().get("padding-top").doubleValue()).isEqualTo(1.5);
        assertThat(padding.declarations().get("padding-right").booleanValue()).isTrue();
    }

    /** Review Focus 3: two definitions writing one property; the later value takes the first position. */
    @Test
    void collidingProperties() {
        List<RecordedCase> cases = recorded("rf3-collide-");
        assertThat(cases).hasSize(5);
        assertThat(cases).allSatisfy(c -> {
            assertThat(c.out()).as("recorded %s", c.id()).isNotNull();
            assertThat(Functions.ours(c.fn(), c.in(), c.args())).as(c.id()).isEqualTo(c.out());
        });
        StyleResult sides =
                StyleEngine.getStyles(json("{\"border\":{\"width\":{\"top\":\"1px\"},\"top\":{\"width\":\"2px\"}}}"));
        assertThat(sides.css()).isEqualTo("border-top-width:2px;");
    }

    /** SE13, G9, G10: the option is a boolean; WordPress's "1" is not strict true. */
    @Test
    void optionsAreTyped() {
        JsonNode g10 = json("{\"color\":{\"text\":\"var:preset|color|a\"}}");
        assertThat(StyleEngine.getStyles(g10, StyleOptions.defaults()))
                .isEqualTo(new StyleResult(
                        "color:var(--wp--preset--color--a);",
                        Map.of("color", json("\"var(--wp--preset--color--a)\"")),
                        "has-text-color has-a-color"));
        JsonNode g9 = json("{\"color\":{\"text\":\"var:preset|color|a\",\"background\":\"#000\"}}");
        assertThat(StyleEngine.getStyles(g9, StyleOptions.defaults().withConvertVarsToClassnames(true)))
                .isEqualTo(new StyleResult(
                        "background-color:#000;",
                        Map.of("background-color", json("\"#000\"")),
                        "has-text-color has-a-color has-background"));
        StyleOptions options = StyleOptions.defaults().withSelector(".a").withConvertVarsToClassnames(true);
        assertThat(options).isEqualTo(new StyleOptions(".a", null, true));
        assertThat(StyleOptions.defaults()).isEqualTo(new StyleOptions(null, null, false));
        assertThat(StyleEngine.getStyles(null)).isEqualTo(StyleResult.EMPTY);
        assertThat(StyleResult.EMPTY.isEmpty()).isTrue();
        assertThatThrownBy(() -> StyleEngine.getStyles(g9, null)).isInstanceOf(NullPointerException.class);
    }

    /** The result's declarations keep the table order and cannot be changed. */
    @Test
    void resultIsAValue() {
        StyleResult r = StyleEngine.getStyles(
                json("{\"typography\":{\"fontSize\":\"1px\"},\"color\":{\"text\":\"red\",\"background\":\"red\"}}"));
        assertThat(r.declarations().keySet()).containsExactly("color", "background-color", "font-size");
        assertThat(r.classnames()).isEqualTo("has-text-color has-background");
        assertThatThrownBy(() -> r.declarations().put("x", json("1")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(r.isEmpty()).isFalse();
    }
}
