package org.quarkword.lib.wpstyleengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessorTest {

    private static final CssDeclarations RED = CssDeclarations.of(Map.of("color", "red"));

    private static List<RecordedCase> recorded(String prefix, int count) {
        List<RecordedCase> cases = RecordedCases.load().stream()
                .filter(c -> c.id().startsWith(prefix))
                .toList();
        assertThat(cases).hasSize(count);
        return cases;
    }

    private static void assertRecorded(List<RecordedCase> cases) {
        assertThat(cases).allSatisfy(c -> assertThat(c.out()).as(c.id()).isNotNull());
        assertThat(cases)
                .allSatisfy(c -> assertThat(Functions.ours(c.fn(), c.in(), c.args()))
                        .as(c.id())
                        .isEqualTo(c.out()));
    }

    /** R4 (D6): the G190 shape; each call reads the store afresh and gives the same text. */
    @Test
    void repeatedCallsAgree() {
        StyleStore store = new StyleStore("o");
        store.addRule(".a", null, RED);
        store.addRule(".b", null, RED);
        StyleProcessor processor = new StyleProcessor().addStore(store);
        assertThat(processor.css(true, false)).isEqualTo(".a,.b{color:red;}");
        assertThat(processor.css(true, false)).isEqualTo(".a,.b{color:red;}");
        assertThat(processor.css(true, false)).isEqualTo(".a,.b{color:red;}");
        assertThat(processor.css()).isEqualTo(".a{color:red;}.b{color:red;}");
        // what the store holds at call time counts
        store.addRule(".c", null, CssDeclarations.of(Map.of("width", "1px")));
        assertThat(processor.css(true, false)).isEqualTo(".c{width:1px;}.a,.b{color:red;}");
        // QuarkWord's answer for se-g190 (WordPress's differs by D6)
        RecordedCase g190 = recorded("se-g190", 1).get(0);
        assertThat(Functions.ours(g190.fn(), g190.in(), g190.args()).toString())
                .isEqualTo("[\".a,.b{color:red;}\",\".a,.b{color:red;}\",\".a{color:red;}.b{color:red;}\"]");
    }

    /** G189b (D6): a store rule merged into a direct rule changes neither of them. */
    @Test
    void callerRulesAreNotChanged() {
        StyleStore store = new StyleStore("m");
        store.addRule(".a", null, CssDeclarations.of(orderedMap("color", "red", "width", "1px")));
        CssRule direct = CssRule.of(".a", CssDeclarations.of(orderedMap("color", "blue", "height", "2px")));
        StyleProcessor processor = new StyleProcessor().addStore(store).addRules(direct);
        assertThat(processor.css()).isEqualTo(".a{color:red;height:2px;width:1px;}");
        assertThat(processor.css()).isEqualTo(".a{color:red;height:2px;width:1px;}");
        assertThat(direct.declarations().declarations())
                .containsExactly(Map.entry("color", "blue"), Map.entry("height", "2px"));
        assertThat(store.rules().get(".a").declarations().declarations())
                .containsExactly(Map.entry("color", "red"), Map.entry("width", "1px"));
        RecordedCase g189b = recorded("se-g189b", 1).get(0);
        assertThat(Functions.ours(g189b.fn(), g189b.in(), g189b.args()).toString())
                .isEqualTo("[[\".a{color:red;height:2px;width:1px;}\"],[{\"color\":\"blue\",\"height\":\"2px\"}]]");
    }

    /** Review Focus 4: the processor keys rules by the untrimmed selector and group, the store by trimmed ones. */
    @Test
    void keyNormalisation() {
        assertRecorded(recorded("rf4-proc-", 3));
        StyleStore store = new StyleStore("s");
        store.addRule(".a", "@media print", CssDeclarations.of(Map.of("width", "1px")));
        assertThat(new StyleProcessor()
                        .addRules(CssRule.of("@media print .a", RED))
                        .addStore(store)
                        .css())
                .isEqualTo("@media print .a{color:red;width:1px;}");
    }

    /** Review Focus 5: optimize on colliding shapes, as WordPress renders them. */
    @Test
    void optimizeCollisions() {
        assertRecorded(recorded("rf5-opt-", 7));
        // G178: the grouped .a takes the ungrouped .a's place in the comparison, and .b turns red
        CssDeclarations blue = CssDeclarations.of(Map.of("color", "blue"));
        assertThat(new StyleProcessor()
                        .addRules(CssRule.of(".a", RED), CssRule.of(".a", blue, "@media print"), CssRule.of(".b", blue))
                        .css(true, false))
                .isEqualTo("@media print{.a{color:blue;}}.a,.b{color:red;}");
    }

    /** G179 (D7): grouped rules only; the combined rule takes the first grouped rule's declarations. */
    @Test
    void groupedOnlyDuplicatesFollowTheFirstRule() {
        assertRecorded(recorded("se-g179", 1));
        CssDeclarations important =
                CssDeclarations.builder().add("color", "red", true).build();
        assertThat(new StyleProcessor()
                        .addRules(
                                CssRule.of(".a", important, "@media print"),
                                CssRule.of(
                                        ".b",
                                        RED.toBuilder()
                                                .add("color", "red", true)
                                                .build(),
                                        "@media screen"))
                        .css(true, false))
                .isEqualTo("@media print{.a{color:red !important;}}@media screen{.b{color:red !important;}}"
                        + ".a,.b{color:red !important;}");
    }

    /** G187, G191: only stores can be added, and a store name added again keeps its first position. */
    @Test
    void storesByName() {
        StyleProcessor processor = new StyleProcessor();
        assertThatThrownBy(() -> processor.addStore(null)).isInstanceOf(NullPointerException.class);
        StyleStore first = new StyleStore("one");
        first.addRule(".one", null, RED);
        StyleStore second = new StyleStore("two");
        second.addRule(".two", null, RED);
        StyleStore again = new StyleStore("one");
        again.addRule(".again", null, RED);
        processor.addStore(first).addStore(second).addStore(first);
        assertThat(processor.css()).isEqualTo(".one{color:red;}.two{color:red;}");
        processor.addStore(again);
        assertThat(processor.css()).isEqualTo(".again{color:red;}.two{color:red;}");
        assertThatThrownBy(() -> processor.addRules((CssRule) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void stylesheetFromCssRules() {
        StyleStore context = new StyleStore("ctx");
        List<CssRuleEntry> entries = List.of(
                CssRuleEntry.of(".a", Map.of("color", "red")),
                CssRuleEntry.of(null, Map.of("color", "red")),
                CssRuleEntry.of("0", Map.of("color", "red")),
                CssRuleEntry.fromJson(".b", null, null),
                CssRuleEntry.fromJson(".c", JsonNodeFactory.instance.textNode("color:red"), null),
                CssRuleEntry.of(".d", CssDeclarations.empty(), null),
                CssRuleEntry.of(".e", Map.of("width", "1px"), "@media print"));
        assertThat(StyleEngine.stylesheetFromCssRules(
                        entries, StylesheetOptions.defaults().withContext(context)))
                .isEqualTo(".a{color:red;}@media print{.e{width:1px;}}");
        // a declarations value is stored even when empty, as WordPress stores an empty declarations object
        assertThat(context.rules().keySet()).containsExactly(".a", ".d", "@media print .e");
        assertThat(StyleEngine.stylesheetFromCssRules(List.of(), StylesheetOptions.defaults()))
                .isEmpty();
        assertThat(StyleEngine.stylesheetFromCssRules(
                        entries, StylesheetOptions.defaults().withOptimize(true).withPrettify(true)))
                .isEqualTo(".a {\n\tcolor: red;\n}\n\n@media print {\n\t.e {\n\t\twidth: 1px;\n\t}\n}\n");
        // D3: a context cannot take a selector that trims to nothing
        assertThatThrownBy(() -> StyleEngine.stylesheetFromCssRules(
                        List.of(CssRuleEntry.of("  ", Map.of("color", "red"))),
                        StylesheetOptions.defaults().withContext(context)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("selector is empty after trimming: \"  \"");
        assertThat(StyleEngine.stylesheetFromCssRules(
                        List.of(CssRuleEntry.of("  ", Map.of("color", "red"))), StylesheetOptions.defaults()))
                .isEqualTo("  {color:red;}");
        assertThatThrownBy(() -> new CssRuleEntry(".a", null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CssRuleEntry(".a", JsonNodeFactory.instance.objectNode(), RED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(StylesheetOptions.defaults()).isEqualTo(new StylesheetOptions(null, false, false));
    }

    private static Map<String, String> orderedMap(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
