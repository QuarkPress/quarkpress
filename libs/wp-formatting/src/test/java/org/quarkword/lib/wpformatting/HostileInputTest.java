package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class HostileInputTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);

    /** Runs {@code work} under the time limit and bounds what it allocates on its own thread. */
    private static <T> T bounded(String input, Supplier<T> work) {
        return assertTimeoutPreemptively(LIMIT, () -> {
            var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long before = threads.getCurrentThreadAllocatedBytes();
            T result = work.get();
            long allocated = threads.getCurrentThreadAllocatedBytes() - before;
            assertThat(allocated)
                    .as("bytes allocated for %d chars", input.length())
                    .isLessThan(200L * input.length() + 10_000_000L);
            return result;
        });
    }

    // Task 1
    @Test
    void htmlSplitAndReplaceAreLinear() {
        String lessThans = "<".repeat(1_000_000);
        // a "<" chunk runs to the next ">" or the end, so the whole run is one markup chunk (WordPress's answer)
        assertThat(bounded(lessThans, () -> Formatting.htmlSplit(lessThans))).containsExactly("", lessThans, "");
        assertThat(bounded(lessThans, () -> Formatting.replaceInHtmlTags(lessThans, Map.of("<", "&lt;"))))
                .isEqualTo("&lt;".repeat(1_000_000));

        String tags = "<a>".repeat(300_000);
        assertThat(bounded(tags, () -> Formatting.htmlSplit(tags))).hasSize(600_001);

        String openers = "<!--".repeat(250_000);
        assertThat(bounded(openers, () -> Formatting.htmlSplit(openers))).hasSize(3);

        String cdata = "<![CDATA[".repeat(100_000);
        assertThat(bounded(cdata, () -> Formatting.htmlSplit(cdata))).hasSize(3);

        String pairs = "<a " + "xy".repeat(500_000) + ">";
        assertThat(bounded(pairs, () -> Formatting.replaceInHtmlTags(pairs, Map.of("x", "1", "xy", "2", "y", "3"))))
                .isEqualTo("<a " + "2".repeat(500_000) + ">");
    }
}
