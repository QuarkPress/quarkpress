package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * tagsIn must not keep each level's consumed hits (and their content copies) alive while it descends: 1 MB
 * inside 200 distinct nested tags needed about 200 MB. The work runs in a child JVM with a 96 MB heap.
 */
class TagsInHeapTest {

    private static final int DEPTH = 200;

    @Test
    void tagsInOnDeepDistinctNestingFitsInASmallHeap() throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Process process = new ProcessBuilder(
                        java,
                        "-Xmx96m",
                        "-XX:TieredStopAtLevel=1",
                        "-cp",
                        System.getProperty("java.class.path"),
                        TagsInHeapTest.class.getName())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("tags=" + DEPTH);
    }

    /** Child JVM entry point. */
    public static void main(String[] args) {
        String[] names = new String[DEPTH];
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        for (int i = 0; i < DEPTH; i++) {
            names[i] = "t" + i;
            open.append("[t").append(i).append(']');
            close.insert(0, "[/t" + i + "]");
        }
        String input = open + "lorem ipsum ".repeat(87_000) + close;
        Shortcodes shortcodes =
                Shortcodes.builder(Registries.constant("X", names)).build();
        System.out.println("tags=" + shortcodes.tagsIn(input).size());
    }
}
