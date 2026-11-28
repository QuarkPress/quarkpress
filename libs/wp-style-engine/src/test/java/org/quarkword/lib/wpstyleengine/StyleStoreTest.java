package org.quarkword.lib.wpstyleengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StyleStoreTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static ObjectNode red() {
        return NODES.objectNode().put("color", "red");
    }

    /** G157, G206 shape (D3): an invalid name throws instead of giving null. */
    @Test
    void invalidNamesThrow() {
        StyleStores stores = new StyleStores();
        assertThatThrownBy(() -> new StyleStore(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"\"");
        assertThatThrownBy(() -> new StyleStore("0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"0\"");
        assertThatThrownBy(() -> stores.store("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stores.store("0")).isInstanceOf(IllegalArgumentException.class);
        assertThat(stores.names()).isEmpty();
    }

    /** G158: the same name gives the same store; stores are listed in creation order. */
    @Test
    void storesAreKeptByName() {
        StyleStores stores = new StyleStores();
        StyleStore x = stores.store("x");
        assertThat(stores.store("x")).isSameAs(x);
        assertThat(x.name()).isEqualTo("x");
        assertThat(stores.find("y")).isEmpty();
        stores.store("default");
        assertThat(stores.names()).containsExactly("x", "default");
        stores.clear();
        assertThat(stores.names()).isEmpty();
    }

    /** G205 (D3): a whitespace-only selector throws instead of a fatal error. */
    @Test
    void whitespaceSelectorThrows() {
        StyleStore store = new StyleStore("s");
        assertThatThrownBy(() -> StyleEngine.storeCssRule(store, "   ", red(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("selector is empty after trimming: \"   \"");
        assertThatThrownBy(() -> store.addRule(" 0 ", null, CssDeclarations.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.rules()).isEmpty();
    }

    @Test
    void emptySelectorIsANoOp() {
        StyleStore store = new StyleStore("s");
        StyleEngine.storeCssRule(store, "", red(), null, null);
        StyleEngine.storeCssRule(store, "0", red(), null, null);
        StyleEngine.storeCssRule(store, null, red(), null, null);
        StyleEngine.storeCssRule(store, ".a", NODES.objectNode(), null, null);
        StyleEngine.storeCssRule(store, ".a", NODES.nullNode(), null, "@media print");
        assertThat(store.rules()).isEmpty();
        StyleEngine.storeCssRule(store, ".b", null, CssDeclarations.empty(), null);
        assertThat(store.rules()).containsOnlyKeys(".b");
        assertThatThrownBy(() -> StyleEngine.storeCssRule(store, ".c", red(), CssDeclarations.empty(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** G159, G160, G202: trimmed keys, merging in place, group keys, exact removal, snapshots. */
    @Test
    void keysMergeAndRemove() {
        StyleStore store = new StyleStore("t");
        store.addRule(" .a ", null, CssDeclarations.of(Map.of("color", "red")));
        store.addRule(".b", "", CssDeclarations.of(Map.of("color", "red")));
        store.addRule(".a", null, CssDeclarations.of(Map.of("width", "1px")));
        store.addRule(".a", " @media print ", CssDeclarations.of(Map.of("color", "blue")));
        store.addRule(
                "@media print .a",
                null,
                CssDeclarations.builder().add("top", "0", true).build());
        Map<String, CssRule> snapshot = store.rules();
        assertThat(snapshot).containsOnlyKeys(".a", ".b", "@media print .a");
        assertThat(snapshot.get(".a").toCss()).isEqualTo(".a{color:red;width:1px;}");
        assertThat(snapshot.get("@media print .a").toCss()).isEqualTo("@media print{.a{color:blue;top:0 !important;}}");
        store.removeRule(".b ");
        store.removeRule(".b");
        store.addRule(".a", null, CssDeclarations.of(Map.of("color", "green")));
        assertThat(store.rules().keySet()).containsExactly(".a", "@media print .a");
        assertThat(store.rules().get(".a").toCss()).isEqualTo(".a{color:green;width:1px;}");
        assertThat(snapshot.get(".a").toCss()).isEqualTo(".a{color:red;width:1px;}");
    }

    /** G186: compile_css. */
    @Test
    void compileCss() {
        CssDeclarations red = CssDeclarations.of(Map.of("color", "red"));
        assertThat(StyleEngine.compileCss(red, null)).isEqualTo("color:red;");
        assertThat(StyleEngine.compileCss(red, "0")).isEqualTo("color:red;");
        assertThat(StyleEngine.compileCss(red, ".a")).isEqualTo(".a{color:red;}");
        assertThat(StyleEngine.compileCss(CssDeclarations.empty(), ".a")).isEmpty();
        assertThat(StyleEngine.compileCss(NODES.textNode("color:red"), ".a")).isEmpty();
        assertThat(StyleEngine.compileCss(NODES.arrayNode(), ".a")).isEmpty();
    }

    /** T2-K (6), D3: raw declarations that are not an array throw instead of adding an empty rule. */
    @Test
    void nonArrayRawDeclarationsThrow() {
        StyleStore store = new StyleStore("s");
        for (var raw : List.of(NODES.textNode("color:red"), NODES.booleanNode(true), NODES.numberNode(1))) {
            assertThatThrownBy(() -> StyleEngine.storeCssRule(store, ".a", raw, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("raw declarations are not an array: " + raw.getNodeType());
        }
        assertThat(store.rules()).isEmpty();
    }

    /** DC3: rules() is an unmodifiable snapshot. */
    @Test
    void rulesAreUnmodifiable() {
        StyleStore store = new StyleStore("s");
        store.addRule(".a", null, CssDeclarations.of(Map.of("color", "red")));
        Map<String, CssRule> rules = store.rules();
        CssRule rule = CssRule.of(".b", CssDeclarations.empty());
        assertThatThrownBy(() -> rules.put(".b", rule)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> rules.remove(".a")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.rules()).containsOnlyKeys(".a");
    }

    /** DC6: two StyleStores instances share nothing. */
    @Test
    void storesInstancesAreIndependent() {
        StyleStores first = new StyleStores();
        StyleStores second = new StyleStores();
        StyleStore a = first.store("x");
        a.addRule(".a", null, CssDeclarations.of(Map.of("color", "red")));
        StyleStore b = second.store("x");
        assertThat(b).isNotSameAs(a);
        assertThat(b.rules()).isEmpty();
        second.store("y");
        assertThat(first.names()).containsExactly("x");
        first.clear();
        assertThat(second.names()).containsExactly("x", "y");
        assertThat(a.rules().get(".a").toCss()).isEqualTo(".a{color:red;}");
    }
}
