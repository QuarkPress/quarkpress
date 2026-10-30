package org.quarkpress.testing.wprestparity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wpscenario.NetworkVariant;
import org.quarkpress.testing.wpscenario.ScenarioDatabase;
import org.quarkpress.testing.wpscenario.WordPressScenarioOracle;

/**
 * WordPress 7.1.3 served over HTTP for REST comparisons: a copy of the scenario harness's subdirectory network in its
 * own database, with pretty permalinks ({@code /%postname%/}), {@code WP_ENVIRONMENT_TYPE} {@code local} and the dev
 * realm's five users with one application password each, served by {@code php -S} on the loopback address. Every
 * request reaches WordPress as one to {@code localhost} on port 80 (a WordPress network cannot run on a port), so
 * WordPress's hrefs read {@code http://localhost/...}. The run's files and the server's log are kept in the module's
 * {@code target/wp-rest-parity/<database>/}.
 */
public final class WordPressRestSite implements AutoCloseable {

    /** The dev realm's users, mirrored on WordPress with the same roles, e-mails and display names. */
    public static final List<String> USERS = List.of("alice", "bob", "carol", "dave", "erin");

    static final String RESOURCES = "php/";
    static final Duration STARTUP = Duration.ofSeconds(30);
    static final long SETUP_TIMEOUT_MINUTES = 5;

    private static final AtomicInteger RUNS = new AtomicInteger();
    /**
     * One oracle per database server, WordPress tree and PHP binary, so each server gets the template network installed
     * once.
     */
    private static final Map<OracleKey, WordPressScenarioOracle> ORACLES = new HashMap<>();

    /** The database is compared by identity: one container, one template installation. */
    private record OracleKey(ScenarioDatabase database, Path tree, String php) {}

    private final Path wordpress;
    private final ScenarioDatabase database;
    private final String php;
    private final String databaseName;
    private final Path runDir;
    private final Map<String, Long> ids = new HashMap<>();
    private final Map<String, String> passwords = new ConcurrentHashMap<>();
    private Process server;
    private URI root;
    private int operations;
    private boolean closed;

    private WordPressRestSite(Path wordpress, ScenarioDatabase database, String php, String databaseName, Path runDir) {
        this.wordpress = wordpress;
        this.database = database;
        this.php = php;
        this.databaseName = databaseName;
        this.runDir = runDir;
    }

    /** Clones the template network, prepares it (rest-setup.php "prepare") and starts php -S on a free port. */
    public static WordPressRestSite start(Path wordpress, ScenarioDatabase database, String php)
            throws IOException, InterruptedException {
        Objects.requireNonNull(wordpress, "wordpress");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(php, "php");
        Path tree = wordpress.toAbsolutePath().normalize();
        oracle(tree, database, php).install(NetworkVariant.SUBDIRECTORY);
        String name = "rest_" + RUNS.incrementAndGet();
        Path runDir = Path.of("target", "wp-rest-parity", name).toAbsolutePath();
        Files.createDirectories(runDir);
        WordPressRestSite site = new WordPressRestSite(tree, database, php, name, runDir);
        try {
            site.writeConfiguration();
            ObjectNode input = OracleJson.MAPPER.createObjectNode();
            input.put("template", NetworkVariant.SUBDIRECTORY.templateDatabase());
            input.put("database", name);
            site.readUsers(site.exec("prepare", input));
            site.serve();
        } catch (IOException | InterruptedException | RuntimeException | Error e) {
            try {
                site.close();
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return site;
    }

    /**
     * The oracle that installed (or will install) the template network on {@code database}. The template database has
     * one name per variant, so a server that already holds it for another tree or PHP binary is refused.
     */
    private static WordPressScenarioOracle oracle(Path tree, ScenarioDatabase database, String php) {
        OracleKey key = new OracleKey(database, tree, php);
        synchronized (ORACLES) {
            for (OracleKey other : ORACLES.keySet()) {
                if (other.database() == database && !other.equals(key)) {
                    throw new IllegalStateException("the database server already holds the template network of "
                            + other.tree() + " with PHP '" + other.php() + "'; start another server for " + tree
                            + " with PHP '" + php + "'");
                }
            }
            return ORACLES.computeIfAbsent(
                    key,
                    k -> new WordPressScenarioOracle(
                            php,
                            tree,
                            database,
                            Path.of("target", "wp-scenario-runs").toAbsolutePath()));
        }
    }

    /** {@code http://127.0.0.1:<port>/}. */
    public URI root() {
        return root;
    }

    /** WordPress's id of a {@link #USERS} entry. */
    public long userId(String user) {
        Long id = ids.get(user);
        if (id == null) {
            throw new IllegalArgumentException("not a site user: " + user);
        }
        return id;
    }

    /** The user's current application password (the one {@link #renewApplicationPassword} made last, if any). */
    public String applicationPassword(String user) {
        String password = passwords.get(user);
        if (password == null) {
            throw new IllegalArgumentException("not a site user: " + user);
        }
        return password;
    }

    /**
     * Creates a new application password for the user (rest-setup.php "app_password") and uses it from then on: {@link
     * #applicationPassword} and {@link RestClient#applicationPasswords} answer the new one.
     */
    public String renewApplicationPassword(String user) throws IOException, InterruptedException {
        userId(user);
        ObjectNode args = OracleJson.MAPPER.createObjectNode();
        args.put("user", user);
        JsonNode answer = operation("app_password", args);
        if (!answer.path("password").isTextual()) {
            throw new IllegalStateException("rest-setup.php app_password did not answer a password: " + answer);
        }
        String password = answer.get("password").textValue();
        passwords.put(user, password);
        return password;
    }

    /** Runs one rest-setup.php operation on the site's database (CLI, not HTTP); returns its JSON answer. */
    public JsonNode operation(String op, ObjectNode args) throws IOException, InterruptedException {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(args, "args");
        return exec(op, args);
    }

    /** Sets an option of the main site (operation {@code option}). */
    public void option(String name, JsonNode value) throws IOException, InterruptedException {
        ObjectNode args = OracleJson.MAPPER.createObjectNode();
        args.put("name", Objects.requireNonNull(name, "name"));
        args.set("value", Objects.requireNonNull(value, "value"));
        operation("option", args);
    }

    /** Stops php -S and drops the site's database. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (server != null) {
            destroyTree(server);
        }
        try (Connection connection = database.connect("");
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot drop the REST site's database " + databaseName, e);
        }
    }

    private Path config() {
        return runDir.resolve("rest-config.php");
    }

    private Map<String, String> environment() {
        Map<String, String> environment = new LinkedHashMap<>(database.environment());
        environment.put("QP_REST_CONFIG", config().toString());
        return environment;
    }

    /**
     * Writes the site's configuration into the run directory, and next to the WordPress directory the wp-config.php
     * WordPress looks for there, which only loads the configuration named by {@code QP_REST_CONFIG}.
     */
    private void writeConfiguration() throws IOException {
        String template = resource("rest-config.php");
        String config = template.replace("{{ABSPATH}}", phpString(wordpress + "/"))
                .replace("{{DB_NAME}}", phpString(databaseName));
        Files.writeString(config(), config, StandardCharsets.UTF_8);
        Path wpConfig = wordpress.getParent().resolve("wp-config.php");
        String loader = "<?php\n// Written by the REST parity harness; regenerated, never committed.\n"
                + "require getenv('QP_REST_CONFIG');\n";
        if (!Files.isRegularFile(wpConfig)
                || !Files.readString(wpConfig, StandardCharsets.UTF_8).equals(loader)) {
            Path temporary = Files.createTempFile(wordpress.getParent(), "wp-config", ".tmp");
            Files.writeString(temporary, loader, StandardCharsets.UTF_8);
            Files.move(temporary, wpConfig, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /** The text inside a single-quoted PHP string. */
    static String phpString(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '\'') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private void readUsers(JsonNode answer) {
        JsonNode users = answer.path("users");
        for (String user : USERS) {
            JsonNode entry = users.path(user);
            if (!entry.path("id").isIntegralNumber() || !entry.path("password").isTextual()) {
                throw new IllegalStateException("rest-setup.php prepare did not answer user " + user + ": " + answer);
            }
            ids.put(user, entry.get("id").longValue());
            passwords.put(user, entry.get("password").textValue());
        }
    }

    private void serve() throws IOException, InterruptedException {
        Path router = install("rest-router.php");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path log = runDir.resolve("php-server.log");
        ProcessBuilder builder = new ProcessBuilder(
                        php,
                        "-d",
                        "memory_limit=512M",
                        "-d",
                        "display_errors=stderr",
                        "-d",
                        "error_reporting=E_ALL & ~E_DEPRECATED",
                        "-S",
                        "127.0.0.1:" + port,
                        "-t",
                        wordpress.toString(),
                        router.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().putAll(environment());
        try {
            server = builder.start();
        } catch (IOException e) {
            throw new AssertionError(
                    "cannot start PHP '" + php + "'; install PHP 8.4 with mysqli or pass -Dwp.php=<path>: "
                            + e.getMessage(),
                    e);
        }
        root = URI.create("http://127.0.0.1:" + port + "/");
        awaitServing(log);
    }

    /** Waits until {@code GET /wp-json/} gets any answer; fails with the server's log when it never does. */
    private void awaitServing(Path log) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + STARTUP.toNanos();
        try (HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2))
                .build()) {
            HttpRequest probe = HttpRequest.newBuilder(root.resolve("wp-json/"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            while (true) {
                if (!server.isAlive()) {
                    throw failure("php -S exited with status " + server.exitValue(), log);
                }
                try {
                    http.send(probe, HttpResponse.BodyHandlers.discarding());
                    return;
                } catch (IOException notYet) {
                    if (System.nanoTime() > deadline) {
                        throw failure("php -S did not answer GET /wp-json/ within " + STARTUP.toSeconds() + " s", log);
                    }
                    Thread.sleep(100);
                }
            }
        }
    }

    private JsonNode exec(String op, ObjectNode input) throws IOException, InterruptedException {
        Path script = install("rest-setup.php");
        String label = op + "-" + ++operations;
        Path in = runDir.resolve(label + "-in.json");
        Path out = runDir.resolve(label + "-out.json");
        Path log = runDir.resolve(label + ".log");
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
                        script.toString(),
                        op,
                        wordpress.toString(),
                        in.toString(),
                        out.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().putAll(environment());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new AssertionError(
                    "cannot start PHP '" + php + "'; install PHP 8.4 with mysqli or pass -Dwp.php=<path>: "
                            + e.getMessage(),
                    e);
        }
        try {
            if (!process.waitFor(SETUP_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                destroyTree(process);
                throw failure(label + " timed out after " + SETUP_TIMEOUT_MINUTES + " minutes", log);
            }
        } catch (InterruptedException e) {
            destroyTree(process);
            throw e;
        }
        if (!Files.exists(out)) {
            throw failure(label + " wrote no output (exit status " + process.exitValue() + ")", log);
        }
        JsonNode result;
        try {
            result = OracleJson.MAPPER.readTree(out.toFile());
        } catch (IOException e) {
            throw failure(label + " wrote unparsable output: " + e.getMessage(), log);
        }
        if (result == null || !result.isObject()) {
            throw failure(label + " wrote no JSON object (exit status " + process.exitValue() + ")", log);
        }
        if (result.has("error")) {
            throw failure(label + " failed: " + result.get("error").asText(), log);
        }
        if (process.exitValue() != 0 || !result.has("out")) {
            throw failure(label + " exited with status " + process.exitValue() + " without an answer", log);
        }
        return result.get("out");
    }

    private Path install(String name) throws IOException {
        Path target = runDir.resolve(name);
        Files.writeString(target, resource(name), StandardCharsets.UTF_8);
        return target;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = WordPressRestSite.class.getResourceAsStream(RESOURCES + name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCES + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static AssertionError failure(String what, Path log) {
        String tail;
        try {
            String text = Files.readString(log, StandardCharsets.UTF_8);
            tail = text.length() <= 4000 ? text : text.substring(text.length() - 4000);
        } catch (IOException e) {
            tail = "(log unreadable: " + e.getMessage() + ")";
        }
        return new AssertionError("WordPress REST site: " + what + "; log: " + log + "\n" + tail);
    }
}
