package org.quarkword.lib.wpstyleengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EntryPointsTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static ObjectNode color(String text) {
        ObjectNode styles = NODES.objectNode();
        styles.putObject("color").put("text", text);
        return styles;
    }

    /** G162, SE32: without a selector (none, "" or "0") nothing is stored, and the CSS is the bare declarations. */
    @Test
    void contextWithoutSelectorStoresNothing() {
        StyleStore store = new StyleStore("block-supports");
        StyleOptions into = StyleOptions.defaults().withContext(store);
        assertThat(StyleEngine.getStyles(color("blue"), into).css()).isEqualTo("color:blue;");
        assertThat(StyleEngine.getStyles(color("blue"), into.withSelector("")).css())
                .isEqualTo("color:blue;");
        assertThat(StyleEngine.getStyles(color("blue"), into.withSelector("0")).css())
                .isEqualTo("color:blue;");
        assertThat(store.rules()).isEmpty();
        // with a selector, two calls merge into one rule: later values in place, new properties appended
        StyleEngine.getStyles(color("red"), into.withSelector(".x"));
        ObjectNode padding = NODES.objectNode();
        padding.putObject("spacing").put("padding", "1px");
        StyleEngine.getStyles(padding, into.withSelector(".x"));
        StyleEngine.getStyles(color("green"), into.withSelector(".x"));
        assertThat(store.rules().keySet()).containsExactly(".x");
        assertThat(StyleEngine.stylesheetFromStore(store, StylesheetOptions.defaults()))
                .isEqualTo(".x{color:green;padding:1px;}");
        // no declarations, nothing stored
        StyleEngine.getStyles(NODES.objectNode(), into.withSelector(".none"));
        assertThat(store.rules().keySet()).containsExactly(".x");
    }

    /** D3: a selector that is empty after trimming cannot key a rule; WordPress stops with a fatal error. */
    @Test
    void whitespaceSelectorWithAContextThrows() {
        StyleStore store = new StyleStore("c");
        StyleOptions into = StyleOptions.defaults().withContext(store);
        assertThatThrownBy(() -> StyleEngine.getStyles(color("red"), into.withSelector(" \t")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\" \t\"");
        assertThatThrownBy(() -> StyleEngine.getStyles(color("red"), into.withSelector(" 0 ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.rules()).isEmpty();
        // without a context the selector is used as given
        assertThat(StyleEngine.getStyles(color("red"), StyleOptions.defaults().withSelector(" \t"))
                        .css())
                .isEqualTo(" \t{color:red;}");
    }

    /** G163, G209: the store holds the raw declarations, and a rejected value is filtered again on every render. */
    @Test
    void rejectedValuesStayInTheStore() {
        StyleStore store = new StyleStore("c");
        ObjectNode styles = color("red}");
        ((ObjectNode) styles.get("color")).put("background", "blue");
        StyleResult result = StyleEngine.getStyles(
                styles, StyleOptions.defaults().withSelector(".z").withContext(store));
        assertThat(result.css()).isEqualTo(".z{background-color:blue;}");
        assertThat(result.classnames()).isEqualTo("has-text-color has-background");
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("color", "red}");
        raw.put("background-color", "blue");
        assertThat(store.rules().get(".z").declarations().declarations()).containsExactlyEntriesOf(raw);
        assertThat(StyleEngine.stylesheetFromStore(store, StylesheetOptions.defaults()))
                .isEqualTo(".z{background-color:blue;}");
        assertThat(StyleEngine.stylesheetFromStore(store, StylesheetOptions.defaults()))
                .isEqualTo(".z{background-color:blue;}");

        // G209: no css at all, the rule is stored anyway
        StyleStore e = new StyleStore("e");
        StyleResult rejected = StyleEngine.getStyles(
                color("red}"), StyleOptions.defaults().withSelector(".e").withContext(e));
        assertThat(rejected.css()).isEmpty();
        assertThat(e.rules().keySet()).containsExactly(".e");
        assertThat(StyleEngine.stylesheetFromStore(e, StylesheetOptions.defaults()))
                .isEmpty();
        assertThat(StyleEngine.stylesheetFromStore(
                        e, StylesheetOptions.defaults().withPrettify(true)))
                .isEqualTo("\n");
    }

    /** G164: an unknown, valid context name creates an empty store and gives "". */
    @Test
    void unknownContextCreatesAnEmptyStore() {
        StyleStores stores = new StyleStores();
        assertThat(StyleEngine.stylesheetFromContext(stores, "nope", StylesheetOptions.defaults()))
                .isEmpty();
        assertThat(stores.names()).containsExactly("nope");
    }

    /** G206, D3: an invalid context name throws, and no store is created. */
    @Test
    void invalidContextNameThrows() {
        StyleStores stores = new StyleStores();
        assertThatThrownBy(() -> StyleEngine.stylesheetFromContext(stores, "", StylesheetOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"\"");
        assertThatThrownBy(() -> StyleEngine.stylesheetFromContext(stores, "0", StylesheetOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"0\"");
        assertThat(stores.names()).isEmpty();
        assertThatThrownBy(() -> StyleEngine.stylesheetFromContext(stores, null, StylesheetOptions.defaults()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StyleEngine.stylesheetFromContext(null, "c", StylesheetOptions.defaults()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StyleEngine.stylesheetFromStore(null, StylesheetOptions.defaults()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StyleEngine.stylesheetFromStore(new StyleStore("c"), null))
                .isInstanceOf(NullPointerException.class);
    }

    /** R4, H10: two registries never share rules, unlike WordPress's process-wide stores. */
    @Test
    void storesAreIndependentPerRegistry() {
        StyleStores first = new StyleStores();
        StyleStores second = new StyleStores();
        StyleEngine.getStyles(
                color("red"), StyleOptions.defaults().withSelector(".a").withContext(first.store("block-supports")));
        StyleEngine.getStyles(
                color("blue"), StyleOptions.defaults().withSelector(".b").withContext(second.store("block-supports")));
        assertThat(StyleEngine.stylesheetFromContext(first, "block-supports", StylesheetOptions.defaults()))
                .isEqualTo(".a{color:red;}");
        assertThat(StyleEngine.stylesheetFromContext(second, "block-supports", StylesheetOptions.defaults()))
                .isEqualTo(".b{color:blue;}");
        assertThat(StyleEngine.stylesheetFromContext(new StyleStores(), "block-supports", StylesheetOptions.defaults()))
                .isEmpty();
    }

    /** SE4: a store's stylesheet is optimized and prettified as asked; the options' context is not used. */
    @Test
    void stylesheetFromStoreTakesTheOptions() {
        StyleStore store = new StyleStore("s");
        store.addRule(".a", null, CssDeclarations.of(Map.of("color", "red")));
        store.addRule(".b", null, CssDeclarations.of(Map.of("color", "red")));
        store.addRule(".c", " @media print ", CssDeclarations.of(Map.of("width", "1px")));
        StyleStore other = new StyleStore("other");
        StylesheetOptions options = StylesheetOptions.defaults().withContext(other);
        assertThat(StyleEngine.stylesheetFromStore(store, options))
                .isEqualTo(".a{color:red;}.b{color:red;}@media print{.c{width:1px;}}");
        assertThat(StyleEngine.stylesheetFromStore(store, options.withOptimize(true)))
                .isEqualTo("@media print{.c{width:1px;}}.a,.b{color:red;}");
        assertThat(StyleEngine.stylesheetFromStore(store, options.withPrettify(true)))
                .isEqualTo(
                        ".a {\n\tcolor: red;\n}\n.b {\n\tcolor: red;\n}\n@media print {\n\t.c {\n\t\twidth: 1px;\n\t}\n}\n");
        assertThat(other.rules()).isEmpty();
        assertThat(store.rules().keySet()).containsExactly(".a", ".b", "@media print .c");
    }

    /** G184: stylesheetFromCssRules with a context fills the store, whose stylesheet is then the same. */
    @Test
    void cssRulesWithAContextFillTheStore() {
        StyleStores stores = new StyleStores();
        List<CssRuleEntry> rules = List.of(
                CssRuleEntry.of(".a", Map.of("color", "red")),
                CssRuleEntry.of(".b", Map.of("width", "1px"), "@media print"));
        String css = StyleEngine.stylesheetFromCssRules(
                rules, StylesheetOptions.defaults().withContext(stores.store("ctx")));
        assertThat(css).isEqualTo(".a{color:red;}@media print{.b{width:1px;}}");
        assertThat(stores.store("ctx").rules().keySet()).containsExactly(".a", "@media print .b");
        assertThat(StyleEngine.stylesheetFromContext(stores, "ctx", StylesheetOptions.defaults()))
                .isEqualTo(css);
    }
}
