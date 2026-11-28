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
        JsonNode attrs = json.readTree("""
                {"style": {"color": {"text": "var:preset|color|vivid-red", "background": "#fff"},
                           "spacing": {"padding": {"top": "var:preset|spacing|40", "left": "1rem"}}}}""");
        StyleStores stores = new StyleStores();
        StyleResult result = StyleEngine.getStyles(
                attrs.path("style"),
                StyleOptions.defaults().withSelector(".wp-container-1").withContext(stores.store("block-supports")));
        assertThat(result.classnames()).isEqualTo("has-text-color has-vivid-red-color has-background");
        assertThat(result.css()).isNotEmpty();
        String stylesheet = StyleEngine.stylesheetFromContext(
                stores, "block-supports", StylesheetOptions.defaults().withOptimize(true));
        assertThat(stylesheet).isEqualTo(result.css());
    }

    @Test
    void globalStyles() throws Exception {
        JsonNode node = json.readTree("""
                {"--wp--preset--color--black": "#000", "background-color": "var(--wp--preset--color--black)"}""");
        String css = StyleEngine.stylesheetFromCssRules(
                List.of(
                        CssRuleEntry.fromJson(":root", node, null),
                        CssRuleEntry.of(".a", Map.of("color", "red"), "@media (min-width: 600px)")),
                StylesheetOptions.defaults().withPrettify(true));
        assertThat(css).isNotEmpty();
    }
}
