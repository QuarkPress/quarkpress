package org.quarkpress.testing.wporacle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Runs one PHP driver over a batch of cases in a single {@code php} process:
 * {@code php -d memory_limit=1G <driver> <wordpress-src-dir> <input.jsonl> <output.jsonl>}. The driver finds
 * {@code bootstrap.php} through the {@code QP_ORACLE_HOME} environment variable. Anything that goes wrong on the
 * PHP side (the binary is missing, a non-zero exit, a timeout, a case that threw, a missing result) fails the
 * calling test with the path of {@code target/wp-oracle-<driver>.log}; it is never reported as a parity
 * difference. Only one {@code php} process runs at a time, also across modules built in parallel: each run holds
 * a file lock in the WordPress source directory.
 */
public final class PhpOracle {

    /** One input: a unique id and the JSON payload the driver receives as {@code in}. */
    public record Case(String id, JsonNode input) {
        public Case {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(input, "input");
        }
    }

    static final long TIMEOUT_MINUTES = 10;
    static final String LOCK = ".oracle.lock";
    private static final List<String> SUPPORT_FILES = List.of("bootstrap.php", "extract-functions.php");

    private final String php;
    private final Path wordpress;
    private final Path workDir;

    public PhpOracle(String php, Path wordpress, Path workDir) {
        this.php = Objects.requireNonNull(php, "php");
        this.wordpress = Objects.requireNonNull(wordpress, "wordpress");
        this.workDir = Objects.requireNonNull(workDir, "workDir");
    }

    /** Uses {@code -Dwp.php} (default {@code php}) and the module's {@code target/} directory. */
    public static PhpOracle create(Path wordpress) {
        return new PhpOracle(
                System.getProperty("wp.php", "php"),
                wordpress,
                Path.of("target").toAbsolutePath());
    }

    /** Runs {@code driver} once over {@code cases}; returns each case's {@code out} value by id, in case order. */
    public Map<String, JsonNode> run(Path driver, List<Case> cases) throws IOException, InterruptedException {
        String name = driver.getFileName().toString().replaceFirst("\\.php$", "");
        Files.createDirectories(workDir);
        Path home = installSupportFiles();
        Path input = workDir.resolve("wp-oracle-" + name + "-in.jsonl");
        Path output = workDir.resolve("wp-oracle-" + name + "-out.jsonl");
        Path log = workDir.resolve("wp-oracle-" + name + ".log");
        writeCases(input, cases);
        Files.deleteIfExists(output);
        ProcessBuilder builder = new ProcessBuilder(
                        php,
                        "-d",
                        "memory_limit=1G",
                        driver.toAbsolutePath().toString(),
                        wordpress.toAbsolutePath().toString(),
                        input.toString(),
                        output.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("QP_ORACLE_HOME", home.toString());
        // modules build in parallel (-T4); the lock in the shared WordPress directory runs one php at a time
        try (FileChannel lockChannel =
                        FileChannel.open(wordpress.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = lockChannel.lock()) {
            Process process;
            try {
                process = builder.start();
            } catch (IOException e) {
                throw new AssertionError(
                        "cannot start PHP '" + php + "'; install PHP 8.4 or pass -Dwp.php=<path>: " + e.getMessage(),
                        e);
            }
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly().waitFor();
                throw failure("timed out after " + TIMEOUT_MINUTES + " minutes", log);
            }
            if (process.exitValue() != 0) {
                throw failure("exited with status " + process.exitValue(), log);
            }
        }
        return readResults(output, cases, log);
    }

    private Path installSupportFiles() throws IOException {
        Path home = workDir.resolve("wp-oracle-php");
        Files.createDirectories(home);
        for (String file : SUPPORT_FILES) {
            try (InputStream in = PhpOracle.class.getResourceAsStream("php/" + file)) {
                if (in == null) {
                    throw new IllegalStateException("missing oracle resource php/" + file);
                }
                Files.copy(in, home.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return home;
    }

    private static void writeCases(Path input, List<Case> cases) throws IOException {
        Set<String> ids = new HashSet<>();
        try (BufferedWriter writer = Files.newBufferedWriter(input, StandardCharsets.UTF_8)) {
            for (Case c : cases) {
                if (!ids.add(c.id())) {
                    throw new IllegalArgumentException("duplicate case id " + c.id());
                }
                ObjectNode line = OracleJson.MAPPER.createObjectNode();
                line.put("id", c.id());
                line.set("in", c.input());
                writer.write(OracleJson.MAPPER.writeValueAsString(line));
                writer.write('\n');
            }
        }
    }

    private static Map<String, JsonNode> readResults(Path output, List<Case> cases, Path log) throws IOException {
        if (!Files.exists(output)) {
            throw failure("wrote no output file " + output, log);
        }
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode row = OracleJson.MAPPER.readTree(line);
                String id = row.path("id").asText();
                if (row.has("error")) {
                    throw failure(
                            "failed on case " + id + ": " + row.get("error").asText(), log);
                }
                byId.put(id, row.get("out"));
            }
        }
        if (byId.size() != cases.size()) {
            throw failure("returned " + byId.size() + " results for " + cases.size() + " cases", log);
        }
        Map<String, JsonNode> ordered = new LinkedHashMap<>();
        for (Case c : cases) {
            JsonNode out = byId.get(c.id());
            if (out == null) {
                throw failure("returned no result for case " + c.id(), log);
            }
            ordered.put(c.id(), out);
        }
        return ordered;
    }

    private static AssertionError failure(String what, Path log) {
        return new AssertionError("PHP oracle " + what + "; log: " + log + "\n" + tail(log));
    }

    private static String tail(Path log) {
        try {
            String text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            return text.length() <= 4000 ? text : text.substring(text.length() - 4000);
        } catch (IOException e) {
            return "(log unreadable: " + e.getMessage() + ")";
        }
    }
}
