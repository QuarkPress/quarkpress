package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class HostileInputTest {

    private static final Duration LIMIT = Duration.ofSeconds(10);
    private static final Shortcodes CONSTANT =
            Shortcodes.builder(Registries.constant("X", "foo", "inner")).build();

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

    @Test
    void openersWithoutClosingBrackets() {
        String input = "[foo".repeat(250_000);
        assertThat(bounded(input, () -> CONSTANT.apply(input))).isEqualTo(input);
        assertThat(bounded(input, () -> CONSTANT.strip(input))).isEqualTo(input);
        assertThat(bounded(input, () -> CONSTANT.has(input, "foo"))).isFalse();
        assertThat(bounded(input, () -> CONSTANT.tagsIn(input))).isEmpty();
        assertThat(bounded(input, () -> CONSTANT.parse(input))).hasSize(1);
    }

    @Test
    void openersWithoutClosingTags() {
        String input = "[foo]".repeat(200_000);
        assertThat(bounded(input, () -> CONSTANT.apply(input))).isEqualTo("X".repeat(200_000));
        assertThat(bounded(input, () -> CONSTANT.tagsIn(input))).hasSize(200_000);
    }

    @Test
    void manyClosingTagLookalikes() {
        String input = "[foo]" + "[/foo".repeat(200_000) + "]";
        Shortcodes length = Shortcodes.builder(ShortcodeRegistry.builder()
                        .add("foo", (s, c) -> String.valueOf(s.content().length()))
                        .build())
                .build();
        assertThat(bounded(input, () -> length.apply(input))).isEqualTo("999995");
    }

    @Test
    void lessThanSignsWithoutGreaterThan() {
        String lone = "<".repeat(1_000_000) + "[foo]";
        assertThat(bounded(lone, () -> CONSTANT.apply(lone))).isEqualTo(lone);
        String plugin = "<[foo]".repeat(200_000);
        assertThat(bounded(plugin, () -> CONSTANT.apply(plugin))).isEqualTo("<X".repeat(200_000));
        String nested = "<b>".repeat(200_000) + "[foo]" + "</b>".repeat(200_000);
        assertThat(bounded(nested, () -> CONSTANT.apply(nested)))
                .isEqualTo("<b>".repeat(200_000) + "X" + "</b>".repeat(200_000));
    }

    @Test
    void theInputThatBreaksWordPresssRegex() { // R15.1: WordPress returns ""; difference D5
        String input = "[inner " + "/a".repeat(600_000);
        assertThat(bounded(input, () -> CONSTANT.apply(input))).isEqualTo(input);
        assertThat(bounded(input, () -> CONSTANT.strip(input))).isEqualTo(input);
    }

    @Test
    void longAttributeText() {
        String input = "[foo " + "a=1 ".repeat(250_000) + "]";
        Shortcodes count = Shortcodes.builder(ShortcodeRegistry.builder()
                        .add("foo", (s, c) -> String.valueOf(s.attributes().size()))
                        .build())
                .build();
        assertThat(bounded(input, () -> count.apply(input))).isEqualTo("1");
    }

    @Test
    void oneElementWithManyAttributes() {
        String input = "<a " + "title=\"[foo]\" ".repeat(50_000) + ">";
        assertThat(bounded(input, () -> CONSTANT.apply(input))).isEqualTo("<a " + "title=\"X\" ".repeat(50_000) + ">");
    }

    @Test
    void escapeBracketRuns() {
        String input = "[[".repeat(500_000) + "[foo]";
        assertThat(bounded(input, () -> CONSTANT.apply(input))).isEqualTo("[".repeat(1_000_000) + "X");
    }

    @Test
    void paragraphsThatNeverClose() {
        String openers = "<p>[foo ".repeat(100_000);
        assertThat(bounded(openers, () -> CONSTANT.unautop(openers))).isEqualTo(openers);
        String shared = "<p>[foo]".repeat(100_000) + "[/foo]" + " ".repeat(100_000) + "x";
        assertThat(bounded(shared, () -> CONSTANT.unautop(shared))).isEqualTo(shared);
    }

    @Test
    void manyNamesWithACommonPrefix() {
        String[] names = new String[10_000];
        for (int i = 0; i < names.length; i++) {
            names[i] = "t" + i;
        }
        Shortcodes many = Shortcodes.builder(Registries.constant("X", names)).build();
        String input = "[t9999]".repeat(100_000);
        assertThat(bounded(input, () -> many.apply(input))).isEqualTo("X".repeat(100_000));
        assertThat(bounded(input, () -> many.tagsIn(input))).hasSize(100_000);
        assertThat(bounded(input, () -> many.unautop("<p>" + input + "</p>"))).isEqualTo("<p>" + input + "</p>");
    }

    @Test
    void deepDistinctNesting() { // O(n * depth), as in WordPress; must finish in a few seconds
        int depth = 200;
        String[] names = new String[depth];
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            names[i] = "t" + i;
            open.append("[t").append(i).append(']');
            close.insert(0, "[/t" + i + "]");
        }
        String text = "lorem ipsum ".repeat(87_000); // about 1 MB
        String input = open + text + close;
        Shortcodes many = Shortcodes.builder(Registries.constant("X", names)).build();
        assertThat(bounded(input, () -> many.has(input, "t" + (depth - 1)))).isTrue();
        assertThat(bounded(input, () -> many.tagsIn(input))).hasSize(depth);
        assertThat(bounded(input, () -> many.apply(input))).isEqualTo("X");
        assertThat(bounded(input, () -> many.strip(input))).isEmpty();
    }
}
