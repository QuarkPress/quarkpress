package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ScenarioIndexTest {

    @Test
    void entriesSkipBlankLinesAndCommentsAndStripEachLine() {
        assertThat(ScenarioIndex.entries("# heading\n  a.json \r\n\n\tb.json\n# c.json\nlast.json"))
                .containsExactly("a.json", "b.json", "last.json");
        assertThat(ScenarioIndex.entries("")).isEmpty();
    }

    @Test
    void theHandWrittenScenariosOfEachFamilyLoadThroughTheIndex() {
        assertThat(ScenarioIndex.load("scenarios/capabilities/")).hasSameSizeAs(CapabilityScenarios.handWritten());
        assertThat(ScenarioIndex.load("scenarios/multisite/")).isEqualTo(MultisiteScenarios.handWritten());
        assertThat(ScenarioIndex.load("scenarios/posts/")).isEqualTo(PostScenarios.handWritten());
    }

    @Test
    void aMissingResourceIsNamed() {
        assertThatThrownBy(() -> ScenarioIndex.load("scenarios/nothing/"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("missing scenario resource scenarios/nothing/index.txt");
    }
}
