package org.quarkword.lib.wpstyleengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The README's usage examples, verbatim, so that they keep compiling and running. */
class ReadmeExampleTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void blockSupports() throws Exception {
        JsonNode style = json.readTree("""
                {"color": {"text": "var:preset|color|vivid-red", "background": "#fff"},
                 "spacing": {"padding": {"top": "var:preset|spacing|40", "left": "1rem"}},
                 "elements": {"link": {"color": {"text": "#00f"}, ":hover": {"color": {"text": "#f00"}}}}}""");

        StyleResult color = StyleEngine.getStyles(
                json.createObjectNode().set("color", style.get("color")),
                StyleOptions.defaults().withConvertVarsToClassnames(true));
        assertThat(color.classnames()).isEqualTo("has-text-color has-vivid-red-color has-background");
        assertThat(color.css()).isEqualTo("background-color:#fff;");
        StyleResult spacing = StyleEngine.getStyles(json.createObjectNode().set("spacing", style.get("spacing")));
        assertThat(spacing.css()).isEqualTo("padding-top:var(--wp--preset--spacing--40);padding-left:1rem;");

        StyleStores stores = new StyleStores();
        String link = ".wp-elements-1 a:where(:not(.wp-element-button))";
        StyleEngine.getStyles(
                style.at("/elements/link"),
                StyleOptions.defaults().withSelector(link).withContext(stores, "block-supports"));
        StyleEngine.getStyles(
                style.at("/elements/link/:hover"),
                StyleOptions.defaults().withSelector(link + ":hover").withContext(stores, "block-supports"));
        StyleEngine.stylesheetFromCssRules(
                List.of(CssRuleEntry.of(".wp-container-core-group-is-layout-1", Map.of("flex-wrap", "nowrap"))),
                StylesheetOptions.defaults().withContext(stores, "block-supports"));

        boolean scriptDebug = false;
        String stylesheet = StyleEngine.stylesheetFromContext(
                stores, "block-supports", StylesheetOptions.defaults().withPrettify(scriptDebug));
        // checked with php against WordPress 7.1.3
        assertThat(stylesheet)
                .isEqualTo(".wp-elements-1 a:where(:not(.wp-element-button)){color:#00f;}"
                        + ".wp-elements-1 a:where(:not(.wp-element-button)):hover{color:#f00;}"
                        + ".wp-container-core-group-is-layout-1{flex-wrap:nowrap;}");
        assertThat(StyleEngine.stylesheetFromContext(
                        stores, "block-supports", StylesheetOptions.defaults().withPrettify(true)))
                .isEqualTo(".wp-elements-1 a:where(:not(.wp-element-button)) {\n\tcolor: #00f;\n}\n"
                        + ".wp-elements-1 a:where(:not(.wp-element-button)):hover {\n\tcolor: #f00;\n}\n"
                        + ".wp-container-core-group-is-layout-1 {\n\tflex-wrap: nowrap;\n}\n");
        assertThat(stores.names()).containsExactly("block-supports");
    }

    @Test
    void globalStylesBackgroundImage() throws Exception {
        JsonNode background = json.readTree("""
                {"background": {"backgroundImage": {"url": "https://example.com/hero.jpg"},
                                "gradient": "linear-gradient(135deg,#000 0%,#fff 100%)"}}""");

        JsonNode value = StyleEngine.getStyles(background).declarations().get("background-image");
        // checked with php against WordPress 7.1.3
        assertThat(value.asText())
                .isEqualTo("linear-gradient(135deg,#000 0%,#fff 100%), url('https://example.com/hero.jpg')");
    }
}
