package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The oracle's handling of the PHP process and of its output file, with shell scripts standing in for PHP. */
class WordPressScenarioOracleProcessTest {

    @TempDir
    Path dir;

    private Path script(String body) throws Exception {
        Path script = dir.resolve("fake-php.sh");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private JsonNode run(Path script, long timeout, TimeUnit unit) throws Exception {
        return WordPressScenarioOracle.execute(
                List.of(script.toString()),
                Map.of(),
                dir.resolve("out.json"),
                dir.resolve("run.log"),
                "run-x",
                timeout,
                unit);
    }

    @Test
    void returnsTheOutputOfASuccessfulRun() throws Exception {
        Path script = script("echo '{\"out\": {\"main\": 1}}' > " + dir.resolve("out.json"));
        assertThat(run(script, 1, TimeUnit.MINUTES).get("main").asInt()).isEqualTo(1);
    }

    @Test
    void aTruncatedOutputFileFailsWithTheTailOfTheLog() throws Exception {
        Path script = script(
                "echo 'PHP Fatal error: out of memory' >&2\necho '{\"out\": {\"ma' > " + dir.resolve("out.json"));
        assertThatThrownBy(() -> run(script, 1, TimeUnit.MINUTES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("run-x")
                .hasMessageContaining("unparsable")
                .hasMessageContaining("PHP Fatal error: out of memory");
    }

    @Test
    void anEmptyOutputFileFailsWithTheTailOfTheLog() throws Exception {
        Path script = script("echo 'died early' >&2\n: > " + dir.resolve("out.json"));
        assertThatThrownBy(() -> run(script, 1, TimeUnit.MINUTES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("died early");
    }

    @Test
    void outputWithNeitherOutNorErrorIsAnIllegalState() throws Exception {
        Path script = script("echo '{\"other\": 1}' > " + dir.resolve("out.json"));
        assertThatThrownBy(() -> run(script, 1, TimeUnit.MINUTES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run-x")
                .hasMessageContaining("neither 'out' nor 'error'");
    }

    @Test
    void aDriverErrorFailsWithItsMessage() throws Exception {
        Path script = script("echo '{\"error\": \"boom\"}' > " + dir.resolve("out.json"));
        assertThatThrownBy(() -> run(script, 1, TimeUnit.MINUTES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("boom");
    }

    @Test
    void aTimeoutDestroysTheProcessAndItsDescendants() throws Exception {
        Path pidFile = dir.resolve("child.pid");
        Path script = script("sleep 300 &\necho $! > " + pidFile + "\nwait");
        // The bound is generous so a slow machine still starts the script (and writes the pid) before the timeout.
        assertThatThrownBy(() -> run(script, 10, TimeUnit.SECONDS))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("timed out");
        assertThat(pidFile).exists();
        assertDead(pidFile);
    }

    @Test
    void anInterruptDestroysTheProcessAndItsDescendants() throws Exception {
        Path pidFile = dir.resolve("child.pid");
        Path script = script("sleep 300 &\necho $! > " + pidFile + "\nwait");
        Throwable[] thrown = new Throwable[1];
        Thread thread = new Thread(() -> {
            try {
                run(script, 5, TimeUnit.MINUTES);
            } catch (Throwable t) {
                thrown[0] = t;
            }
        });
        thread.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(pidFile) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        Thread.sleep(200);
        thread.interrupt();
        thread.join(20_000);
        assertThat(thrown[0]).isInstanceOf(InterruptedException.class);
        assertDead(pidFile);
    }

    private static void assertDead(Path pidFile) throws Exception {
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive)).isEmpty();
    }
}
