package org.quarkpress.it.restwp;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wprestparity.Exchange;
import org.quarkpress.testing.wprestparity.Normalizer;
import org.quarkpress.testing.wprestparity.RestCall;
import org.quarkpress.testing.wprestparity.RestComparison;
import org.quarkpress.testing.wprestparity.RestScenario;
import org.quarkpress.testing.wprestparity.RestScenarioJson;
import org.quarkpress.testing.wprestparity.RestScenarios;
import org.quarkpress.testing.wprestparity.Side;
import org.quarkpress.testing.wprestparity.WordPressRestSite;
import org.quarkpress.testing.wpscenario.ScenarioDatabase;
import org.quarkpress.testing.wpscenario.WordPressTree;

/**
 * The REST API (wp/v2) against WordPress 7.1.3 over HTTP: every scenario of {@link RestScenarios#all()} runs on a fresh
 * subdirectory site on both sides — WordPress served by {@code php -S} on its own MySQL container, QuarkPress this
 * test application with Keycloak tokens — and the answers are compared after normalisation. Deliberate differences
 * are listed in {@code wp-rest-parity-known-differences.txt}, each with its README label; a listed difference that no
 * longer differs fails the run. Runs with {@code -Pwp-parity} only.
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class RestParityDbTest {

    /** Half the comparisons of the first green run, rounded down to a multiple of 50. */
    static final int MIN_COMPARISONS = 3550;

    static final Path KNOWN = Path.of("src", "test", "resources", "wp-rest-parity-known-differences.txt");
    static final Path DUMPS = Path.of("target", "rest-parity");

    @TestHTTPResource("/")
    URL root;

    KeycloakTestClient keycloak = new KeycloakTestClient();

    /** One side's run of a scenario: its refs (starting, setup and created by requests) and its answers. */
    record Run(Map<String, Long> refs, List<Exchange> exchanges) {}

    @Test
    void theRestApiAnswersAsWordPressDoes() throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        ParityRun run = new ParityRun(
                "rest-parity", ParitySkipList.load(KNOWN), Path.of("target", "rest-parity-failures.json"));
        List<RestScenario> scenarios = RestScenarios.all();
        // -Drest.parity.only=d01,m2c-a-0 narrows the run to scenarios whose ids start so (local debugging only: the
        // known differences of the others then match nothing, so such a run never passes)
        String only = System.getProperty("rest.parity.only", "");
        if (!only.isEmpty()) {
            List<String> prefixes = List.of(only.split(","));
            scenarios = scenarios.stream()
                    .filter(s -> prefixes.stream().anyMatch(p -> s.id().startsWith(p)))
                    .toList();
        }
        Files.createDirectories(DUMPS);
        try (ScenarioDatabase database = ScenarioDatabase.start();
                Side wordpress = Side.wordpress(WordPressRestSite.start(
                        WordPressTree.ensure(System.getProperty("wp.version")),
                        database,
                        System.getProperty("wp.php", "php")));
                Side quarkpress = new QuarkPressSide(root.toURI(), keycloak)) {
            for (RestScenario scenario : scenarios) {
                Run wp = run(wordpress, scenario);
                Run qp = run(quarkpress, scenario);
                dump(scenario, wp, qp);
                if (scenario.id().startsWith("m2c-")) {
                    run.generated(scenario.id(), RestScenarioJson.write(scenario));
                }
                new RestComparison(wp.refs(), qp.refs(), scenario.sitePath())
                        .compare(scenario, wp.exchanges(), qp.exchanges(), run);
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
                    .append(m.quarkpress())
                    .append('\n');
        }
        Files.writeString(DUMPS.resolve("differences.tsv"), all);
        run.assertParity(MIN_COMPARISONS);
    }

    /**
     * Creates the scenario's site, runs its setup and sends its requests. An item a request created (a 201 answer with
     * an id) becomes the ref {@code <kind>:new-req<n>}, so later answers name it the same way on both sides.
     */
    static Run run(Side side, RestScenario scenario) throws Exception {
        Side.ScenarioSite site = side.newSite(scenario.id());
        Map<String, Long> refs = new LinkedHashMap<>(site.refs());
        refs.putAll(side.setUp(site.path(), scenario.setup()));
        List<Exchange> exchanges = new ArrayList<>();
        for (int i = 0; i < scenario.requests().size(); i++) {
            RestCall call = scenario.requests().get(i);
            Exchange exchange = side.send(site.path(), RestScenario.resolve(call, refs));
            exchanges.add(exchange);
            if (exchange.status() == 201) {
                JsonNode id = RestComparison.jsonBody(exchange).path("id");
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
        out.putPOJO("quarkpressRefs", qp.refs());
        ArrayNode requests = out.putArray("requests");
        for (int i = 0; i < wp.exchanges().size(); i++) {
            ObjectNode entry = requests.addObject();
            entry.put("method", wp.exchanges().get(i).call().method());
            entry.put("wordpressTarget", wp.exchanges().get(i).call().target());
            entry.put("quarkpressTarget", qp.exchanges().get(i).call().target());
            entry.set("wordpress", side(wp.exchanges().get(i)));
            entry.set("quarkpress", side(qp.exchanges().get(i)));
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
