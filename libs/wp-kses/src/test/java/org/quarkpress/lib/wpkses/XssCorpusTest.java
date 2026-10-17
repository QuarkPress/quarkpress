package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Classic XSS payloads (src/test/resources/xss-corpus.json). Nothing unsafe survives, and every output is
 * byte-identical to the one WordPress 7.1.3 gives (src/test/resources/xss-corpus-expected.json, written by
 * src/test/php/xss-corpus.php); the live-WordPress parity test reuses {@link #corpus()}.
 */
class XssCorpusTest {

    static List<String> corpus() throws IOException {
        List<String> payloads = new ArrayList<>();
        for (JsonNode payload : Examples.JSON.readTree(
                Path.of("src/test/resources/xss-corpus.json").toFile())) {
            payloads.add(payload.asText());
        }
        return payloads;
    }

    @Test
    void noPayloadSurvives() throws IOException {
        Kses wp = Kses.wordPress();
        Kses bare = Kses.builder().preFilters(false).build();
        SafetyChecker post = new SafetyChecker(wp.allowedHtml(KsesContext.POST), wp.uriAttributes(), wp.protocols());
        SafetyChecker data = new SafetyChecker(wp.allowedHtml(KsesContext.DATA), wp.uriAttributes(), wp.protocols());
        List<String> payloads = corpus();
        assertThat(payloads).hasSizeGreaterThan(100);
        for (String payload : payloads) {
            assertThat(post.problems(wp.post(payload))).as("post: %s", payload).isEmpty();
            assertThat(post.problems(bare.post(payload)))
                    .as("bare post: %s", payload)
                    .isEmpty();
            assertThat(data.problems(wp.data(payload))).as("data: %s", payload).isEmpty();
        }
    }

    @Test
    void everyOutputIsWordPressesOutput() throws IOException {
        // Configured like the oracle driver (Examples.kses): default filters or none, the driver's upload URL.
        Kses wp = Kses.builder()
                .preFilters(true)
                .uploadBaseUrl(Examples.UPLOAD_URL)
                .build();
        Kses bare = Kses.builder()
                .preFilters(false)
                .uploadBaseUrl(Examples.UPLOAD_URL)
                .build();
        List<String> payloads = corpus();
        JsonNode expected = Examples.JSON.readTree(
                Path.of("src/test/resources/xss-corpus-expected.json").toFile());
        assertThat(expected).hasSize(payloads.size());
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i++) {
            String payload = payloads.get(i);
            JsonNode row = expected.get(i);
            assertThat(row.get("text").asText()).as(row.get("id").asText()).isEqualTo(payload);
            compare(failures, row, "wp", wp.post(payload));
            compare(failures, row, "bare", bare.post(payload));
            compare(failures, row, "data", wp.data(payload));
        }
        assertThat(failures).as("QuarkPress differs from WordPress").isEmpty();
    }

    private static void compare(List<String> failures, JsonNode row, String field, String actual) {
        String expected = row.get(field).asText();
        if (!expected.equals(actual)) {
            failures.add(row.get("id").asText() + " " + field + "  input " + row.get("text") + "\n    expected "
                    + row.get(field) + "\n    actual   " + Examples.JSON.valueToTree(actual));
        }
    }

    @Test
    void theCheckerItselfCatchesUnsafeMarkup() {
        Kses wp = Kses.wordPress();
        SafetyChecker post = new SafetyChecker(wp.allowedHtml(KsesContext.POST), wp.uriAttributes(), wp.protocols());
        assertThat(post.problems("<script>x</script>")).isNotEmpty();
        assertThat(post.problems("<a onclick=\"x\">y</a>")).isNotEmpty();
        assertThat(post.problems("<a href=\"javascript:x\">y</a>")).isNotEmpty();
        assertThat(post.problems("<a href=\"/?q=a:b\" title=\"javascript:x\">y</a>"))
                .isEmpty();
    }
}
