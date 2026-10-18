package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;

class ScenarioComparisonTest {

    @TempDir
    Path tmp;

    @Test
    void everyAspectOfEitherSideIsCompared() {
        Outcome wordpress = new Outcome();
        wordpress.created(1, 1);
        wordpress.lookedUp(2, "#1");
        Outcome quarkpress = new Outcome();
        quarkpress.created(1, 1);
        quarkpress.lookedUp(2, Outcome.NONE);
        quarkpress.site(1, "a.example", "/", 1, false, false, false, false);
        ParityRun run = new ParityRun("demo", ParitySkipList.empty(), tmp.resolve("failures.json"));
        ScenarioComparison.compare(run, "demo@subdirectory", wordpress, quarkpress);
        assertThat(run.comparisons()).isEqualTo(3);
        assertThat(run.mismatches())
                .containsExactly(
                        new ParityRun.Mismatch("demo@subdirectory", "op2:lookup", "#1", "none"),
                        new ParityRun.Mismatch(
                                "demo@subdirectory",
                                "site#1",
                                "(absent)",
                                "a.example/ public=1 archived=0 mature=0 spam=0 deleted=0"));
    }

    @Test
    void anAspectOnlyWordPressRecordedAndARepeatedAspectAreHandled() {
        Outcome wordpress = new Outcome();
        wordpress.created(1, 1);
        Outcome quarkpress = new Outcome();
        ParityRun run = new ParityRun("demo", ParitySkipList.empty(), tmp.resolve("failures.json"));
        ScenarioComparison.compare(run, "demo@subdirectory", wordpress, quarkpress);
        ScenarioComparison.compare(run, "none@subdirectory", new Outcome(), new Outcome());
        assertThat(run.comparisons()).isEqualTo(1);
        assertThat(run.mismatches())
                .containsExactly(
                        new ParityRun.Mismatch("demo@subdirectory", "op1:create_site", "created #1", "(absent)"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> wordpress.created(1, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the aspect op1:create_site was recorded twice");
    }
}
