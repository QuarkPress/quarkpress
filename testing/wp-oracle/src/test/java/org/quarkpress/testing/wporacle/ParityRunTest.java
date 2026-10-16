package org.quarkpress.testing.wporacle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParityRunTest {

    @TempDir
    Path tmp;

    @Test
    void passesWhenEverythingMatchesAndEnoughRan() throws IOException {
        Path failures = tmp.resolve("failures.json");
        ParityRun run = new ParityRun("lib-x", ParitySkipList.empty(), failures);
        assertThat(run.compare("a", "tree", "x", "x")).isTrue();
        assertThat(run.compare("b", "flag", true, true)).isTrue();
        run.assertParity(2);
        assertThat(run.comparisons()).isEqualTo(2);
        assertThat(failures).doesNotExist();
    }

    @Test
    void minimumGuardRejectsAnEmptyCorpus() {
        ParityRun run = new ParityRun("lib-x", ParitySkipList.empty(), tmp.resolve("f.json"));
        assertThatThrownBy(() -> run.assertParity(1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("only 0 comparisons ran");
    }

    @Test
    void differencesFailWithBothValues() {
        ParityRun run = new ParityRun("lib-x", ParitySkipList.empty(), tmp.resolve("f.json"));
        assertThat(run.compare("case-1", "serialize", "wp", "qp")).isFalse();
        assertThat(run.mismatches()).hasSize(1);
        assertThatThrownBy(() -> run.assertParity(1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("lib-x differs from WordPress")
                .hasMessageContaining("case-1 [serialize]")
                .hasMessageContaining("wordpress:  wp")
                .hasMessageContaining("quarkpress: qp");
    }

    @Test
    void skippedComparisonsDoNotCountAndStaleSkipsFail() {
        ParitySkipList skips = ParitySkipList.parse("audit-1 tree #1 known difference", "s");
        ParityRun run = new ParityRun("lib-x", skips, tmp.resolve("f.json"));
        assertThat(run.compare("audit-1", "tree", "a", "a")).isTrue();
        run.compare("other", "tree", "a", "a");
        assertThat(run.comparisons()).isEqualTo(1);
        assertThatThrownBy(() -> run.assertParity(1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("stale");
    }

    @Test
    void generatedFailuresAreWrittenWithTheShrunkInput() throws Exception {
        Path file = tmp.resolve("target/wp-parity-failures.json");
        ParityRun run = new ParityRun("lib-x", ParitySkipList.empty(), file);
        run.generated("gen/1", "long input");
        run.generated("gen/2", "fine");
        run.compare("gen/1", "tree", "a", "b");
        run.compare("gen/2", "tree", "a", "a");
        run.shrunk("gen/1", "in");

        assertThat(run.failedGeneratedCases()).containsExactly("gen/1");
        assertThatThrownBy(() -> run.assertParity(1)).isInstanceOf(AssertionError.class);
        JsonNode written = OracleJson.MAPPER.readTree(file.toFile());
        assertThat(written.size()).isEqualTo(1);
        assertThat(written.get(0).get("id").asText()).isEqualTo("gen/1");
        assertThat(written.get(0).get("input").asText()).isEqualTo("long input");
        assertThat(written.get(0).get("shrunk").asText()).isEqualTo("in");
        assertThat(written.get(0).get("differences").get(0).get("aspect").asText())
                .isEqualTo("tree");
    }
}
