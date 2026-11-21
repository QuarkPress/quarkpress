package org.quarkpress.it.keycloak;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Decides, before Quarkus starts, whether the end-to-end tests can run on the quarkpress-keycloak checkout's files:
 * the packaged provider jar (system property {@value #JAR}) that Dev Services mount into Keycloak, and the
 * WordPress-made hashes (system property {@value #HASHES}). This module's POM sets both, from the checkout ({@value
 * #DIR}) beside this repository or {@code -DquarkpressKeycloakDir}.
 *
 * <p>A missing file, or a jar older than the checkout's sources or POMs, disables the tests locally; with {@code
 * -D}{@value #REQUIRE}{@code =true} (CI) it fails them instead, so a skip never passes there. A checkout at another
 * commit than the root POM's pin ({@value #PIN}) is logged as a warning.
 */
public final class ProviderJarCondition implements ExecutionCondition {

    private static final Logger LOG = Logger.getLogger(ProviderJarCondition.class.getName());

    /** The system property naming the provider jar. */
    public static final String JAR = "keycloakProviderJar";

    /** The system property naming the fixture file of WordPress-made hashes. */
    public static final String HASHES = "keycloakHashes";

    /** The system property naming the quarkpress-keycloak checkout. */
    public static final String DIR = "quarkpressKeycloakDir";

    /** The system property carrying the root POM's pin of the provider commit. */
    public static final String PIN = "quarkpressKeycloakCommit";

    /** The system property that, when {@code true}, makes missing or stale files a failure. */
    public static final String REQUIRE = "requireKeycloakProvider";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        List<String> problems = problems();
        if (problems.isEmpty()) {
            pinWarning(System.getProperty(DIR), System.getProperty(PIN)).ifPresent(LOG::warning);
        }
        return decide(problems, Boolean.getBoolean(REQUIRE));
    }

    /** Why the end-to-end tests cannot run with the system properties' files; none when they can. */
    public static List<String> problems() {
        return problems(System.getProperty(JAR), System.getProperty(HASHES), System.getProperty(DIR));
    }

    /**
     * Why the end-to-end tests cannot run: one text per missing file ({@code jar}, {@code hashes}), and one when the
     * jar is older than a file of the checkout {@code dir} it is built from.
     */
    static List<String> problems(String jar, String hashes, String dir) {
        List<String> problems = new ArrayList<>();
        boolean jarPresent = present(jar, JAR, "provider jar", problems);
        present(hashes, HASHES, "WordPress hash fixtures", problems);
        if (jarPresent && dir != null && !dir.isBlank()) {
            Path checkout = Path.of(dir);
            Optional<Path> newer =
                    newestSource(checkout).filter(source -> modified(source).compareTo(modified(Path.of(jar))) > 0);
            newer.ifPresent(source -> problems.add("SKIPPED: provider jar " + jar + " is older than its sources ("
                    + source + "); rebuild it in " + checkout + " (mvn package)"));
        }
        return problems;
    }

    /** Enabled without problems; else disabled, or a failure when the provider is required. */
    static ConditionEvaluationResult decide(List<String> problems, boolean required) {
        if (problems.isEmpty()) {
            return ConditionEvaluationResult.enabled("the quarkpress-keycloak provider jar and fixtures are present");
        }
        String reason = String.join("; ", problems);
        if (required) {
            throw new IllegalStateException("-D" + REQUIRE + "=true, but the end-to-end tests cannot run: " + reason);
        }
        return ConditionEvaluationResult.disabled(reason);
    }

    /** A warning when the checkout {@code dir} is at another commit than {@code pin}; none when either is unknown. */
    static Optional<String> pinWarning(String dir, String pin) {
        if (dir == null || dir.isBlank() || pin == null || pin.length() != 40 || pin.startsWith("$")) {
            return Optional.empty();
        }
        return headCommit(Path.of(dir))
                .filter(head -> !head.equals(pin))
                .map(head -> "the quarkpress-keycloak checkout " + dir + " is at " + head
                        + ", not at the pinned commit " + pin + " (quarkpress-keycloak.commit in the root POM)");
    }

    /** The commit the checkout's {@code .git/HEAD} names: a detached commit, a loose branch ref or a packed one. */
    static Optional<String> headCommit(Path dir) {
        Path git = dir.resolve(".git");
        Optional<String> head = firstLine(git.resolve("HEAD"));
        if (head.isEmpty()) {
            return Optional.empty();
        }
        String text = head.get();
        if (!text.startsWith("ref: ")) {
            return Optional.of(text);
        }
        String ref = text.substring("ref: ".length()).strip();
        Optional<String> loose = firstLine(git.resolve(ref));
        if (loose.isPresent()) {
            return loose;
        }
        try {
            if (!Files.isRegularFile(git.resolve("packed-refs"))) {
                return Optional.empty();
            }
            for (String line : Files.readAllLines(git.resolve("packed-refs"), UTF_8)) {
                if (line.endsWith(" " + ref) && line.indexOf(' ') == 40) {
                    return Optional.of(line.substring(0, 40));
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The file the system property {@code property} names. */
    public static Path file(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("the system property " + property + " is not set");
        }
        return Path.of(value);
    }

    private static boolean present(String value, String property, String what, List<String> problems) {
        if (value == null || value.isBlank() || !Files.isRegularFile(Path.of(value))) {
            problems.add("SKIPPED: " + what + " not found at " + value
                    + "; build QuarkPress/quarkpress-keycloak (mvn package) beside this repository or set -D"
                    + property);
            return false;
        }
        return true;
    }

    /** The most recently modified of the checkout's main sources and its two POMs. */
    private static Optional<Path> newestSource(Path checkout) {
        List<Path> sources = new ArrayList<>();
        sources.add(checkout.resolve("pom.xml"));
        sources.add(checkout.resolve("wp-password/pom.xml"));
        Path main = checkout.resolve("wp-password/src/main");
        if (Files.isDirectory(main)) {
            try (Stream<Path> files = Files.walk(main)) {
                files.filter(Files::isRegularFile).forEach(sources::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return sources.stream()
                .filter(Files::isRegularFile)
                .max((a, b) -> modified(a).compareTo(modified(b)));
    }

    private static FileTime modified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<String> firstLine(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            List<String> lines = Files.readAllLines(file, UTF_8);
            return lines.isEmpty() || lines.get(0).isBlank()
                    ? Optional.empty()
                    : Optional.of(lines.get(0).strip());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
