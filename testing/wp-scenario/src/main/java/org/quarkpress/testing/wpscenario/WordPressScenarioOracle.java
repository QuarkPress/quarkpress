package org.quarkpress.testing.wpscenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.quarkpress.testing.wporacle.OracleJson;

/**
 * Installs WordPress networks into the scenario database and runs scenarios against them with the PHP driver
 * ({@code php/scenario-driver.php}), one PHP process per installation and per scenario. Anything that goes wrong on the
 * PHP side fails the calling test with the tail of the call's log in {@code target/wp-scenario-runs/}; it is never
 * reported as a parity difference.
 */
public final class WordPressScenarioOracle {

    static final String DRIVER = "php/scenario-driver.php";
    static final long TIMEOUT_MINUTES = 5;

    private final String php;
    private final Path wordpress;
    private final ScenarioDatabase database;
    private final Path workDir;
    private final Map<String, JsonNode> installed = new HashMap<>();
    private int runs;

    public WordPressScenarioOracle(String php, Path wordpress, ScenarioDatabase database, Path workDir) {
        this.php = Objects.requireNonNull(php, "php");
        this.wordpress = Objects.requireNonNull(wordpress, "wordpress");
        this.database = Objects.requireNonNull(database, "database");
        this.workDir = Objects.requireNonNull(workDir, "workDir");
    }

    /** Uses {@code -Dwp.php} (default {@code php}) and the module's {@code target/wp-scenario-runs}. */
    public static WordPressScenarioOracle create(Path wordpress, ScenarioDatabase database) {
        return new WordPressScenarioOracle(
                System.getProperty("wp.php", "php"),
                wordpress,
                database,
                Path.of("target", "wp-scenario-runs").toAbsolutePath());
    }

    /**
     * Installs single-site WordPress into the variant's template database and converts it into the variant's network,
     * once per variant; returns the driver's installation report.
     */
    public JsonNode install(NetworkVariant variant) throws IOException, InterruptedException {
        JsonNode report = installed.get(variant.name());
        if (report == null) {
            ObjectNode input = OracleJson.MAPPER.createObjectNode();
            input.set("variant", ScenarioJson.variantJson(variant));
            input.put("database", variant.templateDatabase());
            report = exec("install", "install-" + variant.name(), input);
            installed.put(variant.name(), report);
        }
        return report;
    }

    /** Runs a scenario on a fresh copy of its variant's installation; returns what the driver observed. */
    public JsonNode run(Scenario scenario) throws IOException, InterruptedException {
        install(scenario.variant());
        runs++;
        ObjectNode input = OracleJson.MAPPER.createObjectNode();
        input.set("variant", ScenarioJson.variantJson(scenario.variant()));
        input.put("template", scenario.variant().templateDatabase());
        input.put("database", "scn_" + runs);
        input.set("scenario", ScenarioJson.toJson(scenario));
        return exec("run", "run-" + runs + "-" + scenario.id(), input);
    }

    private JsonNode exec(String mode, String label, ObjectNode input) throws IOException, InterruptedException {
        Files.createDirectories(workDir);
        Path driver = installDriver();
        Path in = workDir.resolve(label + "-in.json");
        Path out = workDir.resolve(label + "-out.json");
        Path log = workDir.resolve(label + ".log");
        OracleJson.MAPPER.writeValue(in.toFile(), input);
        Files.deleteIfExists(out);
        ProcessBuilder builder = new ProcessBuilder(
                        php,
                        "-d",
                        "memory_limit=512M",
                        "-d",
                        "display_errors=stderr",
                        "-d",
                        "error_reporting=E_ALL & ~E_DEPRECATED",
                        driver.toString(),
                        mode,
                        wordpress.toAbsolutePath().toString(),
                        in.toString(),
                        out.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().putAll(database.environment());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new AssertionError(
                    "cannot start PHP '" + php + "'; install PHP 8.4 with mysqli or pass -Dwp.php=<path>: "
                            + e.getMessage(),
                    e);
        }
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly().waitFor();
            throw failure(label + " timed out after " + TIMEOUT_MINUTES + " minutes", log);
        }
        if (!Files.exists(out)) {
            throw failure(label + " wrote no output (exit status " + process.exitValue() + ")", log);
        }
        JsonNode result = OracleJson.MAPPER.readTree(out.toFile());
        if (result.has("error")) {
            throw failure(label + " failed: " + result.get("error").asText(), log);
        }
        if (process.exitValue() != 0) {
            throw failure(label + " exited with status " + process.exitValue(), log);
        }
        return result.get("out");
    }

    private Path installDriver() throws IOException {
        Path driver = workDir.resolve("scenario-driver.php");
        try (InputStream in = WordPressScenarioOracle.class.getResourceAsStream(DRIVER)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + DRIVER);
            }
            Files.copy(in, driver, StandardCopyOption.REPLACE_EXISTING);
        }
        return driver;
    }

    private static AssertionError failure(String what, Path log) {
        String tail;
        try {
            String text = Files.readString(log, StandardCharsets.UTF_8);
            tail = text.length() <= 4000 ? text : text.substring(text.length() - 4000);
        } catch (IOException e) {
            tail = "(log unreadable: " + e.getMessage() + ")";
        }
        return new AssertionError("PHP scenario driver: " + what + "; log: " + log + "\n" + tail);
    }
}
