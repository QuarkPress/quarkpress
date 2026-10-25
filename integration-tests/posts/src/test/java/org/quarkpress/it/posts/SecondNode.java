package org.quarkpress.it.posts;

import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * A second process of the packaged application under test, launched the way the first node was packaged: the
 * runner jar under {@code target} with the JVM flags of {@code quarkus.test.arg-line} (the flags the launcher gives
 * the first node; {@code -XX:TieredStopAtLevel=1} when it is blank), or the native executable. It listens on a free
 * port of localhost, shares the first node's Dev Services database and Keycloak through the same properties, runs under
 * the profile {@code quarkus.test.integration-test-profile} names when it is set (the launcher passes that profile
 * to the first node), reads the test database from {@code QUARKPRESS_TEST_DB}, and logs to
 * {@code target/second-node.log}, whose first line is the command. {@link #start} returns once {@code GET /it/me}
 * answers; {@link #close} stops it, as does a shutdown hook should the test JVM die first.
 */
final class SecondNode implements AutoCloseable {

    private static final Duration START_LIMIT = Duration.ofSeconds(120);

    private static final Duration STEP = Duration.ofMillis(500);

    private static final Duration STOP_LIMIT = Duration.ofSeconds(30);

    private static final int LOG_TAIL = 40;

    private static final Path TARGET = Path.of("target");

    private static final Path LOG = TARGET.resolve("second-node.log");

    private final Process process;

    private final int port;

    private final Thread hook;

    private SecondNode(Process process, int port) {
        this.process = process;
        this.port = port;
        this.hook = new Thread(process::destroyForcibly, "second-node-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
    }

    static SecondNode start(Map<String, String> devServicesProperties) throws IOException {
        Properties artifact = new Properties();
        try (InputStream in = Files.newInputStream(TARGET.resolve("quarkus-artifact.properties"))) {
            artifact.load(in);
        }
        String type = artifact.getProperty("type");
        String path = artifact.getProperty("path");
        int port = freePort();
        List<String> command = new ArrayList<>();
        if ("jar".equals(type)) {
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(jvmFlags());
            command.addAll(systemProperties(port, devServicesProperties));
            command.add("-jar");
            command.add(TARGET.resolve(path).toString());
        } else if ("native".equals(type)) {
            command.add(TARGET.resolve(path).toString());
            command.addAll(systemProperties(port, devServicesProperties));
        } else {
            throw new IllegalStateException("the second node cannot launch artifact type " + type);
        }
        Files.writeString(LOG, "command: " + String.join(" ", command) + System.lineSeparator());
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(LOG.toFile()));
        String db = System.getenv("QUARKPRESS_TEST_DB");
        builder.environment().put("QUARKPRESS_TEST_DB", db == null ? "postgresql" : db);
        Process process = builder.start();
        SecondNode node = new SecondNode(process, port);
        node.awaitReady();
        return node;
    }

    int port() {
        return port;
    }

    @Override
    public void close() {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException shuttingDown) {
            // the JVM is already shutting down: the hook runs by itself
        }
        process.destroy();
        try {
            if (!process.waitFor(STOP_LIMIT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static List<String> jvmFlags() {
        String argLine = System.getProperty("quarkus.test.arg-line");
        if (argLine == null || argLine.isBlank()) {
            return List.of("-XX:TieredStopAtLevel=1");
        }
        List<String> flags = new ArrayList<>();
        for (String flag : argLine.trim().split(" ")) {
            if (!flag.isEmpty()) {
                flags.add(flag);
            }
        }
        return flags;
    }

    private static List<String> systemProperties(int port, Map<String, String> devServicesProperties) {
        List<String> arguments = new ArrayList<>();
        arguments.add("-Dquarkus.http.port=" + port);
        arguments.add("-Dquarkus.http.host=localhost");
        devServicesProperties.forEach((key, value) -> arguments.add("-D" + key + "=" + value));
        String profile = System.getProperty("quarkus.test.integration-test-profile");
        if (profile != null && !profile.isBlank()) {
            arguments.add("-Dquarkus.profile=" + profile);
        }
        return arguments;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void awaitReady() throws IOException {
        Instant deadline = Instant.now().plus(START_LIMIT);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                fail("the second node exited with " + process.exitValue() + "\n" + logTail());
            }
            if (answers()) {
                return;
            }
            try {
                Thread.sleep(STEP.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                close();
                throw new IllegalStateException("interrupted while the second node started", interrupted);
            }
        }
        close();
        fail("the second node did not answer within " + START_LIMIT.toSeconds() + " s\n" + logTail());
    }

    private boolean answers() {
        try {
            HttpURLConnection connection = (HttpURLConnection)
                    URI.create("http://localhost:" + port + "/it/me").toURL().openConnection();
            connection.setConnectTimeout((int) STEP.toMillis());
            connection.setReadTimeout((int) STEP.toMillis());
            try {
                return connection.getResponseCode() == 200;
            } finally {
                connection.disconnect();
            }
        } catch (IOException notYet) {
            return false;
        }
    }

    private String logTail() throws IOException {
        if (!Files.exists(LOG)) {
            return "(no log)";
        }
        List<String> lines = Files.readAllLines(LOG);
        int from = Math.max(0, lines.size() - LOG_TAIL);
        return String.join("\n", lines.subList(from, lines.size()));
    }
}
