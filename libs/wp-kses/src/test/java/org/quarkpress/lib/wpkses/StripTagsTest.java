package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class StripTagsTest {

    @Test
    void matchesWordPress() {
        Examples.assertFile("strip.json");
    }

    @Test
    void scriptAndStyleNamesIgnoreCaseInTheClosingTag() {
        assertThat(Kses.stripAllTags("<Script>a</SCRIPT>b", false)).isEqualTo("b");
    }

    @Test
    void escapedQuotesInsideAPhpBlockDoNotHideTheEnd() {
        // Expected values computed by wp_strip_all_tags in WordPress 7.1.3.
        assertThat(Kses.stripAllTags("<?a \\\" ?>b", false)).isEqualTo("b");
        assertThat(Kses.stripAllTags("<?a \\' ?>b", false)).isEqualTo("b");
        assertThat(Kses.stripAllTags("<?a \\\"x\\\" ?>b", false)).isEqualTo("b");
        assertThat(Kses.stripAllTags("<?a \"\\\" ?>b", false)).isEmpty();
        assertThat(Kses.stripAllTags("<?a \\\\\" ?>b", false)).isEqualTo("b");
    }

    @Test
    void hostileInputStaysLinear() {
        String[] inputs = {
            "<script".repeat(200_000),
            "<script>".repeat(200_000),
            "<".repeat(1_000_000),
            "<!--".repeat(250_000),
            "<?".repeat(500_000),
            "<a \"".repeat(250_000)
        };
        for (String in : inputs) {
            // Each input is one unterminated construct, so PHP's strip_tags leaves only the text before it.
            String out = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> Kses.stripAllTags("x " + in, true));
            assertThat(out).isEqualTo("x");
        }
    }
}
