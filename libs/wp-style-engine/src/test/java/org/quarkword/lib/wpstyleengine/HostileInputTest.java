package org.quarkword.lib.wpstyleengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HostileInputTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);

    /**
     * Runs {@code work} under the time limit and bounds what it allocates on its own thread by the input's
     * {@code size} (characters; a JSON input passes its character count).
     */
    private static <T> T bounded(String label, long size, Supplier<T> work) {
        return assertTimeoutPreemptively(LIMIT, () -> {
            var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long before = threads.getCurrentThreadAllocatedBytes();
            T result = work.get();
            long allocated = threads.getCurrentThreadAllocatedBytes() - before;
            assertThat(allocated)
                    .as("bytes allocated for %s (%d chars)", label, size)
                    .isLessThan(200L * size + 10_000_000L);
            return result;
        });
    }

    // Task 1: the helpers; the first two are also the parity cases t1-kebab-1mb and t1-kebab-words-1mb
    @Test
    void helpersAreLinear() {
        String letters = "a".repeat(1_000_000);
        assertThat(bounded("kebab letters", letters.length(), () -> KebabCase.of(letters)))
                .isEqualTo(letters);
        String words = "a ".repeat(500_000);
        assertThat(bounded("kebab words", words.length(), () -> KebabCase.of(words)))
                .isEqualTo(String.join("-", Collections.nCopies(500_000, "a")));
        String upper = "A".repeat(1_000_000);
        assertThat(bounded("sanitize_key upper", upper.length(), () -> Keys.sanitizeKey(upper)))
                .isEqualTo("a".repeat(1_000_000));
        String accents = "\u00e9".repeat(1_000_000);
        assertThat(bounded("sanitize_key accents", accents.length(), () -> Keys.sanitizeKey(accents)))
                .isEmpty();
    }

    // Task 1: shapes that make a backtracking word matcher give characters back, one run after another
    @Test
    void kebabBacktrackingShapes() {
        String acronym = "A".repeat(1_000_000) + "1";
        assertThat(bounded("kebab acronym", acronym.length(), () -> KebabCase.of(acronym)))
                .isEqualTo("a".repeat(1_000_000) + "-1");
        String misc = "\u0101".repeat(500_000) + "A".repeat(500_000) + "1";
        assertThat(bounded("kebab misc then upper", misc.length(), () -> KebabCase.of(misc)))
                .isEqualTo("\u0101".repeat(500_000) + "-" + "a".repeat(500_000) + "-1");
        String digits = "1".repeat(1_000_000) + "st";
        assertThat(bounded("kebab ordinal", digits.length(), () -> KebabCase.of(digits)))
                .isEqualTo(digits);
        String lowerThenDigit = "a".repeat(1_000_000) + "1";
        assertThat(bounded("kebab lower digit", lowerThenDigit.length(), () -> KebabCase.of(lowerThenDigit)))
                .isEqualTo("a".repeat(1_000_000) + "-1");
        String camel = "aB".repeat(500_000);
        assertThat(bounded("kebab camel", camel.length(), () -> KebabCase.of(camel)))
                .isEqualTo("a" + "-ba".repeat(499_999) + "-b");
        // T1-R: the parity cases t1-kebab-digits-1mb and t1-kebab-prefix-1mb (WordPress: "" and "foo-bar", D1)
        String plainDigits = "1".repeat(1_000_000);
        assertThat(bounded("kebab digits", plainDigits.length(), () -> KebabCase.of(plainDigits)))
                .isEqualTo(plainDigits);
        String prefix = "fooBar " + "A".repeat(1_000_000) + "1";
        assertThat(bounded("kebab prefix", prefix.length(), () -> KebabCase.of(prefix)))
                .isEqualTo("foo-bar-" + "a".repeat(1_000_000) + "-1");
    }

    // Task 2: declarations, rules and stores; the first four are also the parity cases t2-decl-*
    @Test
    void declarationsAndRulesAreLinear() {
        ObjectNode many = JsonNodeFactory.instance.objectNode();
        IntStream.range(0, 10_000).forEach(i -> many.put("--p" + i, "1"));
        assertThat(bounded(
                        "10k declarations",
                        10_000L * 8,
                        () -> CssDeclarations.fromJson(many).toCss()))
                .isEqualTo(IntStream.range(0, 10_000)
                        .mapToObj(i -> "--p" + i + ":1;")
                        .collect(Collectors.joining()));
        String letters = "a".repeat(1_000_000);
        assertThat(bounded(
                        "1 MB value",
                        letters.length(),
                        () -> CssDeclarations.of(Map.of("color", letters)).toCss()))
                .isEqualTo("color:" + letters + ";");
        String semicolons = "red;".repeat(250_000);
        assertThat(bounded(
                        "250k declarations in one value",
                        semicolons.length(),
                        () -> CssDeclarations.of(Map.of("color", semicolons)).toCss()))
                .isEqualTo("color:" + semicolons);
        String only = ";".repeat(1_000_000);
        assertThat(bounded(
                        "1 MB of semicolons",
                        only.length(),
                        () -> CssDeclarations.of(Map.of("color", only)).toCss()))
                // WordPress's answer: safecss keeps the bare "color:" part
                .isEqualTo("color:;");
        String selectors = ".a,".repeat(100_000) + ".b";
        CssDeclarations red = CssDeclarations.of(Map.of("color", "red"));
        assertThat(bounded(
                        "100k selectors",
                        selectors.length(),
                        () -> CssRule.of(selectors, red).toCss(true, 0)))
                .isEqualTo(".a,\n".repeat(100_000) + ".b {\n\tcolor: red;\n}");
        StyleStore merged = bounded("10k merges into one rule", 10_000L * 8, () -> {
            StyleStore store = new StyleStore("s");
            for (int i = 0; i < 10_000; i++) {
                store.addRule(".a", null, CssDeclarations.of(Map.of("--p" + i, "1")));
            }
            return store;
        });
        assertThat(merged.rules()).hasSize(1);
        assertThat(merged.rules().get(".a").toCss())
                .isEqualTo(".a{"
                        + IntStream.range(0, 10_000)
                                .mapToObj(i -> "--p" + i + ":1;")
                                .collect(Collectors.joining())
                        + "}");
        StyleStore distinct = bounded("10k rules", 10_000L * 8, () -> {
            StyleStore store = new StyleStore("s");
            for (int i = 0; i < 10_000; i++) {
                store.addRule(".r" + i, null, red);
            }
            return store;
        });
        assertThat(distinct.rules().keySet())
                .containsExactlyElementsOf(
                        IntStream.range(0, 10_000).mapToObj(i -> ".r" + i).toList());
    }
}
