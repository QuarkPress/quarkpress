package org.quarkword.it.restwp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkword.testing.wporacle.OracleJson;
import org.quarkword.testing.wporacle.ParityRun;
import org.quarkword.testing.wporacle.ParitySkipList;
import org.quarkword.testing.wprestparity.Exchange;
import org.quarkword.testing.wprestparity.MediaKnownDifferences;
import org.quarkword.testing.wprestparity.Normalizer;
import org.quarkword.testing.wprestparity.RestCall;
import org.quarkword.testing.wprestparity.RestComparison;
import org.quarkword.testing.wprestparity.RestScenario;
import org.quarkword.testing.wprestparity.RestScenarioJson;
import org.quarkword.testing.wprestparity.RestScenarios;
import org.quarkword.testing.wprestparity.Side;
import org.quarkword.testing.wprestparity.WordPressRestSite;
import org.quarkword.testing.wpscenario.ScenarioDatabase;
import org.quarkword.testing.wpscenario.WordPressScenarioOracle;
import org.quarkword.testing.wpscenario.WordPressTree;

/**
 * The REST API (wp/v2) against WordPress 7.1.3 over HTTP: every scenario of {@link RestScenarios#all()} runs on a fresh
 * subdirectory site on both sides (WordPress served by {@code php -S} on its own MySQL container, QuarkWord this
 * test application with Keycloak tokens), and the answers are compared after normalisation. Deliberate differences
 * are listed in {@code wp-rest-parity-known-differences.txt} (the REST API's, labelled by the REST extension's README)
 * and {@code wp-front-parity-known-differences.txt} (the front-end scenarios', labelled by the front extension's
 * README), each with its README label; a listed difference that no
 * longer differs fails the run, and so does a scenario that compared nothing (every aspect of it known to differ), a
 * missing scenario, or fewer comparisons than {@link #MIN_COMPARISONS}. Runs with {@code -Pwp-parity} only, and then
 * fails without a container runtime instead of skipping.
 *
 * <p>Scenarios marked {@code editor} need PHP's GD on WordPress's side (the reference image editor), which the run asks
 * WordPress once ({@code extensions}). Without it each is skipped with {@link #EDITOR_SKIP} (its known differences are
 * then left out of the run, as nothing can show them; QuarkWord's side still plays it, so a scenario that cannot run
 * fails here and not first in CI), unless {@code -Dwp.media.require-editor=true}, which fails the run instead. Lines
 * whose reason says {@code (needs gd_webp)} or {@code (needs gd_avif)} apply only when GD writes that format, and are
 * left out and counted otherwise ({@link MediaKnownDifferences}). The installing super admin is the current user while
 * WordPress's template network is created (harness rule, as the media parity run), so the network's upload types are
 * the ones an administrator's installation writes. QuarkWord's stored files of earlier runs ({@code sites/} of the
 * storage directory) are removed first, as the new sites reuse their ids.
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class RestParityDbTest {

    /**
     * 98% of the comparisons of the run that compared the front-end answers' framing and referrer headers and their
     * cookies' paths and attributes (30367 over 206 scenarios, without GD's editor scenarios), rounded down to a
     * multiple of 50.
     */
    static final int MIN_COMPARISONS = 29750;

    static final String EDITOR_SKIP =
            "SKIPPED (needs an image editor): php8.4-gd is not installed on this host; the CI parity cell installs it";

    static final Path KNOWN = Path.of("src", "test", "resources", "wp-rest-parity-known-differences.txt");

    static final Path KNOWN_FRONT = Path.of("src", "test", "resources", "wp-front-parity-known-differences.txt");
    static final Path DUMPS = Path.of("target", "rest-parity");

    @TestHTTPResource("/")
    URL root;

    @ConfigProperty(name = "quarkword.media.fs.directory")
    String storageDirectory;

    KeycloakTestClient keycloak = new KeycloakTestClient();

    /** One side's run of a scenario: its refs (starting, setup and created by requests) and its answers. */
    record Run(Map<String, Long> refs, List<Exchange> exchanges) {}

    @Test
    void theRestApiAnswersAsWordPressDoes() throws Exception {
        assertThat(ScenarioDatabase.runtimeAvailable())
                .as("the REST parity run (-Pwp-parity) needs a container runtime")
                .isTrue();
        List<RestScenario> scenarios = RestScenarios.all();
        // -Drest.parity.only=d01,rest-a-0 narrows the run to scenarios whose ids start so (local debugging only: the
        // known differences of the others then match nothing, so such a run never passes)
        String only = System.getProperty("rest.parity.only", "");
        if (only.isEmpty()) {
            assertThat(RestScenarios.handWritten()).as("hand-written scenarios").isNotEmpty();
            assertThat(scenarios)
                    .as("every hand-written scenario and %d generated ones per seed", RestScenarios.GENERATED)
                    .hasSize(RestScenarios.handWritten().size() + RestScenarios.SEEDS.size() * RestScenarios.GENERATED);
        } else {
            List<String> prefixes = List.of(only.split(","));
            scenarios = scenarios.stream()
                    .filter(s -> prefixes.stream().anyMatch(p -> s.id().startsWith(p)))
                    .toList();
        }
        Files.createDirectories(DUMPS);
        removeTree(insideTarget(Path.of(storageDirectory, "sites")));
        List<String> comparedNothing = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        ParityRun run;
        try (QuarkWordSide quarkword = new QuarkWordSide(root.toURI(), keycloak);
                ScenarioDatabase database = ScenarioDatabase.start();
                Side wordpress = Side.wordpress(start(database))) {
            assertUsersInWordPressOrder(quarkword.userIds());
            JsonNode extensions =
                    ((Side.WordPress) wordpress).site().operation("extensions", OracleJson.MAPPER.createObjectNode());
            boolean gd = extensions.path("gd").asBoolean();
            if (!gd && Boolean.getBoolean("wp.media.require-editor")) {
                fail("php8.4-gd is required (-Dwp.media.require-editor=true) but WordPress's PHP has no GD");
            }
            if (gd && scenarios.stream().anyMatch(RestScenario::editor)) {
                // the reference editor is GD alone: WordPress prefers Imagick when both are loaded
                assertThat(extensions.path("imagick").asBoolean())
                        .as("WordPress's PHP must not load Imagick: the parity reference is WordPress with GD")
                        .isFalse();
            }
            for (RestScenario scenario : scenarios) {
                if (scenario.editor() && !gd) {
                    skipped.add(scenario.id());
                }
            }
            List<String> knownLines = new ArrayList<>(Files.readAllLines(KNOWN, StandardCharsets.UTF_8));
            knownLines.addAll(Files.readAllLines(KNOWN_FRONT, StandardCharsets.UTF_8));
            MediaKnownDifferences.Effective known = MediaKnownDifferences.effective(
                    knownLines,
                    skipped,
                    extensions.path("gd_webp").asBoolean(),
                    extensions.path("gd_avif").asBoolean());
            System.out.println("rest-parity: GD " + gd + "; " + skipped.size() + " editor scenarios skipped, "
                    + known.leftOutForFormats() + " known differences left out for a format GD lacks");
            Path effective = DUMPS.resolve("known-differences.effective.txt");
            Files.writeString(effective, known.text(), StandardCharsets.UTF_8);
            run = new ParityRun(
                    "rest-parity", ParitySkipList.load(effective), Path.of("target", "rest-parity-failures.json"));
            for (RestScenario scenario : scenarios) {
                if (skipped.contains(scenario.id())) {
                    System.out.println("rest-parity: " + scenario.id() + " " + EDITOR_SKIP);
                    // QuarkWord still plays it, so a scenario that cannot run fails here
                    Run qp = run(quarkword, scenario);
                    dump(scenario, new Run(Map.of(), List.of()), qp);
                    continue;
                }
                Run wp = run(wordpress, scenario);
                Run qp = run(quarkword, scenario);
                dump(scenario, wp, qp);
                if (RestScenarios.isGenerated(scenario.id())) {
                    run.generated(scenario.id(), RestScenarioJson.write(scenario));
                }
                int before = run.comparisons();
                new RestComparison(wp.refs(), qp.refs(), scenario.sitePath())
                        .compare(scenario, wp.exchanges(), qp.exchanges(), run);
                if (run.comparisons() == before) {
                    comparedNothing.add(scenario.id());
                }
            }
        }
        System.out.println("rest-parity: " + scenarios.size() + " scenarios, " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        StringBuilder all = new StringBuilder();
        for (ParityRun.Mismatch m : run.mismatches()) {
            all.append(m.caseId())
                    .append('\t')
                    .append(m.aspect())
                    .append('\t')
                    .append(m.wordpress())
                    .append('\t')
                    .append(m.quarkword())
                    .append('\n');
        }
        Files.writeString(DUMPS.resolve("differences.tsv"), all);
        run.assertParity(MIN_COMPARISONS);
        assertThat(comparedNothing)
                .as("scenarios every aspect of which is a known difference")
                .isEmpty();
    }

    /**
     * Fails unless the five users' QuarkWord ids rise in WordPress's order of creation (alice, bob, carol, dave, erin),
     * as the scenarios' id-ordered answers ({@code users?orderby=id}) need. The test application creates the five
     * profiles in that order at start ({@code ItFixtureUsers}); a test that logged in a user first would otherwise
     * decide the order.
     */
    static void assertUsersInWordPressOrder(Map<String, Long> ids) {
        List<String> order = List.of("alice", "bob", "carol", "dave", "erin");
        assertThat(ids.keySet()).as("the five users").containsExactlyElementsOf(order);
        List<Long> values = order.stream().map(ids::get).toList();
        assertThat(values)
                .as(
                        "QuarkWord ids of %s must rise in WordPress's order of creation, or every id-ordered user list"
                                + " differs; got %s (a test provisioned a user before the test application's start did)",
                        order, ids)
                .isSorted()
                .doesNotHaveDuplicates();
    }

    /** WordPress with the installing super admin as the current user while its template network is created. */
    private static WordPressRestSite start(ScenarioDatabase database) throws Exception {
        System.setProperty(WordPressScenarioOracle.INSTALLER_CURRENT_USER, "true");
        try {
            return WordPressRestSite.start(
                    WordPressTree.ensure(System.getProperty("wp.version")),
                    database,
                    System.getProperty("wp.php", "php"));
        } finally {
            System.clearProperty(WordPressScenarioOracle.INSTALLER_CURRENT_USER);
        }
    }

    /**
     * {@code path} made absolute and normalised, when it lies inside this module's {@code target/} directory; otherwise
     * the run fails, so a storage directory configured elsewhere (an environment override) is never emptied.
     */
    static Path insideTarget(Path path) {
        Path target = Path.of("target").toAbsolutePath().normalize();
        Path resolved = path.toAbsolutePath().normalize();
        if (!resolved.startsWith(target) || resolved.equals(target)) {
            throw new IllegalStateException("refusing to remove " + resolved + ": the parity run only removes stored"
                    + " files inside " + target + " (quarkword.media.fs.directory points elsewhere)");
        }
        return resolved;
    }

    /** Removes a directory with everything in it; nothing when it does not exist. */
    static void removeTree(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    /**
     * Creates the scenario's site, runs its setup and sends its requests. An item a request created (a 201 answer with
     * an id, or an {@code _envelope} answer whose status is 201) becomes the ref {@code <kind>:new-req<n>}, so later
     * answers name it the same way on both sides.
     */
    static Run run(Side side, RestScenario scenario) throws Exception {
        Side.ScenarioSite site = side.newSite(scenario.id());
        Map<String, Long> refs = new LinkedHashMap<>(site.refs());
        refs.putAll(side.setUp(site.path(), scenario));
        List<Exchange> exchanges = new ArrayList<>();
        for (int i = 0; i < scenario.requests().size(); i++) {
            RestCall call = scenario.requests().get(i);
            Exchange exchange = side.send(site.path(), scenario.call(i, refs));
            exchanges.add(exchange);
            JsonNode answer = RestComparison.jsonBody(exchange);
            if (exchange.status() == 200 && answer.path("status").asInt() == 201 && answer.has("headers")) {
                // an _envelope answer: the created item is its body
                answer = answer.path("body");
            } else if (exchange.status() != 201) {
                answer = com.fasterxml.jackson.databind.node.MissingNode.getInstance();
            }
            if (!answer.isMissingNode()) {
                JsonNode id = answer.path("id");
                Normalizer.Kind kind = Normalizer.Kind.ofTarget(call.target());
                if (id.isIntegralNumber() && kind != Normalizer.Kind.ANY) {
                    refs.put(kind.prefix() + ":new-req" + (i + 1), id.longValue());
                }
            }
        }
        return new Run(refs, exchanges);
    }

    /** Writes both sides' answers to {@code target/rest-parity/<id>.json}, for reading a difference. */
    static void dump(RestScenario scenario, Run wp, Run qp) throws Exception {
        ObjectNode out = OracleJson.MAPPER.createObjectNode();
        out.put("id", scenario.id());
        out.putPOJO("wordpressRefs", wp.refs());
        out.putPOJO("quarkwordRefs", qp.refs());
        ArrayNode requests = out.putArray("requests");
        for (int i = 0; i < qp.exchanges().size(); i++) {
            ObjectNode entry = requests.addObject();
            entry.put("method", qp.exchanges().get(i).call().method());
            if (i < wp.exchanges().size()) {
                entry.put("wordpressTarget", wp.exchanges().get(i).call().target());
                entry.set("wordpress", side(wp.exchanges().get(i)));
            }
            entry.put("quarkwordTarget", qp.exchanges().get(i).call().target());
            entry.set("quarkword", side(qp.exchanges().get(i)));
        }
        OracleJson.MAPPER
                .writerWithDefaultPrettyPrinter()
                .writeValue(DUMPS.resolve(scenario.id() + ".json").toFile(), out);
    }

    private static ObjectNode side(Exchange exchange) {
        ObjectNode node = OracleJson.MAPPER.createObjectNode();
        node.put("status", exchange.status());
        ObjectNode headers = node.putObject("headers");
        exchange.headers()
                .forEach((name, lines) ->
                        headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(" | ", lines)));
        node.put("body", exchange.text());
        return node;
    }
}
