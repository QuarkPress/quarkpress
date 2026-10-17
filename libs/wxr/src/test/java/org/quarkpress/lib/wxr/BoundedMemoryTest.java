package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * One 200 MB value in a JVM with a 64 MiB heap must fail at the value limit, not with OutOfMemoryError: neither the
 * JDK parser nor the reader may buffer a whole CDATA section or text node. (Many items in a small heap is
 * {@link StreamingMemoryTest}.)
 */
class BoundedMemoryTest {

    @Test
    void hugeCdataValueFailsAtTheLimit() throws Exception {
        assertThat(run("cdata")).contains("VALUE_TOO_LARGE");
    }

    @Test
    void hugeTextValueFailsAtTheLimit() throws Exception {
        assertThat(run("text")).contains("VALUE_TOO_LARGE");
    }

    private static String run(String mode) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(
                        java.toString(),
                        "-Xmx64m",
                        "-XX:+UseSerialGC",
                        "-XX:TieredStopAtLevel=1",
                        "-XX:ActiveProcessorCount=2",
                        "-cp",
                        System.getProperty("java.class.path"),
                        BoundedMemoryMain.class.getName(),
                        mode)
                .redirectErrorStream(true)
                .start();
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (java.io.InputStream in = process.getInputStream()) {
                in.transferTo(captured);
            } catch (java.io.IOException ignored) {
                // the child was killed
            }
        });
        reader.start();
        boolean finished = false;
        try {
            finished = process.waitFor(5, TimeUnit.MINUTES);
            reader.join(10_000);
        } finally {
            if (!finished || process.isAlive()) {
                process.destroyForcibly();
            }
        }
        String output = captured.toString(StandardCharsets.UTF_8);
        assertThat(finished).as("child JVM finished").isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        return output;
    }
}
