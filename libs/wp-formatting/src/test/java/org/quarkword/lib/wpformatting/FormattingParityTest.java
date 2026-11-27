package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkword.testing.wporacle.ParityRun;
import org.quarkword.testing.wporacle.ParitySkipList;
import org.quarkword.testing.wporacle.PhpOracle;
import org.quarkword.testing.wporacle.WordPressRelease;
import org.quarkword.testing.wporacle.WordPressSources;

/**
 * Compares this library with WordPress 7.1.3's formatting functions (testing/wp-oracle) on every case of the
 * shared case file, on large programmatic inputs and on generated inputs. A recorded output that differs from
 * WordPress's answer is reported as the aspect "recorded". Active only with {@code -Pwp-parity}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class FormattingParityTest {

    static final Path DRIVER = Path.of("src/test/php/formatting-driver.php");
    static final Path SKIPS = Path.of("src/test/resources/wp-parity-skip.txt");
    static final Path FAILURES = Path.of("target/wp-parity-failures.json");
    static final String[] WORDPRESS_FILES = {
        "wp-includes/compat.php",
        "wp-includes/formatting.php",
        "wp-includes/kses.php",
        "wp-includes/shortcodes.php",
        "wp-includes/functions.php",
        "wp-includes/blocks.php",
        "wp-includes/class-wp-token-map.php",
        "wp-includes/html-api/html5-named-character-references.php",
        "wp-includes/html-api/class-wp-html-decoder.php"
    };
    static final int MIN_COMPARISONS = 1;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** One case for the oracle; {@code out} is the recorded output or {@code null}. */
    record ParityCase(String id, String fn, String in, ObjectNode args, JsonNode out) {}

    @Test
    void matchesWordPress() throws Exception {
        Path wordpress = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), WORDPRESS_FILES);
        PhpOracle oracle = PhpOracle.create(wordpress);

        List<ParityCase> cases = new ArrayList<>();
        for (RecordedCase c : RecordedCases.load()) {
            if (c.oracle()) {
                cases.add(new ParityCase(c.id(), c.fn(), c.in(), c.args(), c.out()));
            }
        }
        cases.addAll(programmaticCases());
        cases.addAll(generatedCases());

        List<PhpOracle.Case> batch = new ArrayList<>();
        for (ParityCase c : cases) {
            batch.add(new PhpOracle.Case(c.id(), payload(c)));
        }
        Map<String, JsonNode> results = oracle.run(DRIVER, batch);

        ParityRun run = new ParityRun("lib-wp-formatting", ParitySkipList.load(SKIPS), FAILURES);
        for (ParityCase c : cases) {
            JsonNode wp = results.get(c.id());
            assertThat(wp).as("WordPress's answer for %s", c.id()).isNotNull();
            JsonNode ours = Functions.ours(c.fn(), c.in(), c.args());
            run.compare(c.id(), c.fn(), wp.get("out"), ours);
            if (c.out() != null) {
                run.compare(c.id(), "recorded", wp.get("out"), c.out());
            }
        }
        System.out.println("lib-wp-formatting parity: " + cases.size() + " cases, " + run.comparisons()
                + " comparisons, " + run.mismatches().size() + " differences");
        run.assertParity(MIN_COMPARISONS);
    }

    private static ObjectNode payload(ParityCase c) {
        ObjectNode in = NODES.objectNode();
        in.put("fn", c.fn());
        in.put("in", c.in());
        in.set("args", c.args());
        return in;
    }

    /** Inputs too large for the case file; Tasks 2 to 7 add theirs. */
    private static List<ParityCase> programmaticCases() {
        List<ParityCase> cases = new ArrayList<>();
        // Task 2: the readers' large and hostile wpautop inputs
        cases.add(autop("fa-g153", "Line of text\n".repeat(20_000) + "\n" + "Para\n\n".repeat(20_000)));
        cases.add(autop("fa-g154", "a" + " ".repeat(200_000) + "b"));
        cases.add(autop("fa-g155", "< x\n\n".repeat(50_000)));
        cases.add(autop("fa-g156", "<script>".repeat(20_000) + "x\n".repeat(1_000)));
        cases.add(autop("fa-g157", "<blockquote " + "\"a\" ".repeat(50_000)));
        cases.add(autop("fa-g159", "word ".repeat(1_000_000)));
        // the largest <li paragraph WordPress still formats: one more char and its backtrack limit gives ""
        cases.add(autop("fa-li-1mb", "<li>" + "x".repeat(999_999)));
        cases.add(autop("fa-g160", "<li>" + "x".repeat(3_000_000)));
        // repeated "<p><li" hits the same limit much sooner: 27,778 still formats, 27,779 (166,674 chars) gives ""
        cases.add(autop("fa-p-li-27778", "<p><li".repeat(27_778)));
        cases.add(autop("fa-p-li-100k", "<p><li".repeat(100_000)));
        // a script, style, svg or math opener without a closer: 999,992 chars after it still format, 999,993 give ""
        cases.add(autop("fa-script-999992", "<script>" + "x".repeat(999_992)));
        cases.add(autop("fa-script-1mb", "<script>" + "x".repeat(1_000_000)));
        cases.add(autop("fa-style-999992", "<style>" + "x".repeat(999_992)));
        cases.add(autop("fa-style-1mb", "<style>" + "x".repeat(1_000_000)));
        // with blank lines after the opener far less is needed: 111,111 "x\n\n" still format, 111,112 give ""
        cases.add(autop("fa-svg-333k-below", "<svg>" + "x\n\n".repeat(111_111)));
        cases.add(autop("fa-svg-333k", "<svg>" + "x\n\n".repeat(111_112)));
        return cases;
    }

    private static ParityCase autop(String id, String in) {
        return new ParityCase(id, "autop", in, NODES.objectNode(), null);
    }

    /** Generated inputs (Task 8). */
    private static List<ParityCase> generatedCases() {
        return List.of();
    }
}
