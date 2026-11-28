package org.quarkword.lib.wpstyleengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CssRuleTest {

    private static final CssDeclarations RED = CssDeclarations.of(Map.of("color", "red"));

    /** Review Focus 4: the recorded rf4-rule cases, and their plain and pretty forms typed out. */
    @Test
    void oddSelectors() {
        List<RecordedCase> cases = RecordedCases.load().stream()
                .filter(c -> c.id().startsWith("rf4-rule-"))
                .toList();
        assertThat(cases).hasSize(6);
        assertThat(cases)
                .allSatisfy(c -> assertThat(Functions.ours(c.fn(), c.in(), c.args()))
                        .as(c.id())
                        .isEqualTo(c.out()));
        assertThat(CssRule.of(",", RED).toCss(true, 0)).isEqualTo(",\n {\n\tcolor: red;\n}");
        assertThat(CssRule.of(".a,,.b", RED).toCss(true, 0)).isEqualTo(".a,\n,\n.b {\n\tcolor: red;\n}");
        assertThat(CssRule.of("\t.a\n,\n.b", RED).toCss()).isEqualTo("\t.a\n,\n.b{color:red;}");
        assertThat(CssRule.of("", RED, "@media print").toCss()).isEqualTo("@media print{{color:red;}}");
    }

    /** G155 (D2): a rule holds a value, so a declarations value built later is a different value. */
    @Test
    void declarationsAreValues() {
        CssDeclarations source =
                CssDeclarations.builder().add("color", "red", true).build();
        CssRule rule = CssRule.of(".a", source);
        CssDeclarations changed = source.with("width", "1px");
        assertThat(rule.toCss()).isEqualTo(".a{color:red !important;}");
        assertThat(rule.declarations()).isSameAs(source).isNotEqualTo(changed);
        assertThat(rule.withDeclarations(changed).toCss()).isEqualTo(".a{color:red !important;width:1px;}");
        assertThat(source.declarations()).containsOnlyKeys("color");
    }

    @Test
    void nullGroupIsNoGroupAndNothingIsTrimmed() {
        CssRule rule = CssRule.of(" .a ", RED, null);
        assertThat(rule.rulesGroup()).isEmpty();
        assertThat(rule.selector()).isEqualTo(" .a ");
        assertThat(rule.toCss()).isEqualTo(" .a {color:red;}");
        assertThat(CssRule.of(".a", RED, "0").toCss()).isEqualTo(".a{color:red;}");
    }

    /** As php's str_repeat: a negative pretty indent throws even when every declaration is rejected. */
    @Test
    void negativeIndentThrowsOnEmptyRule() {
        CssRule rejected = CssRule.of(".a", CssDeclarations.of(Map.of("color", "expression(")));
        assertThat(rejected.toCss()).isEmpty();
        assertThatThrownBy(() -> rejected.toCss(true, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CssRule.of(".a", RED).toCss(true, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
