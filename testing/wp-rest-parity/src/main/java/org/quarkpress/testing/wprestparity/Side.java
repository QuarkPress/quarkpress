package org.quarkpress.testing.wprestparity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wprestparity.RestScenario.SetupStep;

/**
 * One side of the comparison: WordPress ({@code php -S}) or QuarkPress (the test application). Each scenario gets a
 * fresh subdirectory site with the five users; the setup runs through the side's own API; then the requests are sent.
 */
public interface Side extends AutoCloseable {

    /** A scenario's fresh site: its path and the refs it starts with ("t:uncategorized" and "u:<user>" for the five). */
    record ScenarioSite(String path, Map<String, Long> refs) {
        public ScenarioSite {
            Objects.requireNonNull(path, "path");
            refs = Map.copyOf(refs);
            for (String ref : RestScenario.STARTING_REFS) {
                if (!refs.containsKey(ref)) {
                    throw new IllegalStateException("the new site " + path + " has no starting ref " + ref);
                }
            }
        }
    }

    /** Creates {@code /<id>/} with the five users. */
    ScenarioSite newSite(String scenarioId) throws Exception;

    /** Runs the steps on the site; answers ref to id ("t:news", "p:1", ...) for what they created. */
    Map<String, Long> setUp(String sitePath, List<SetupStep> steps) throws Exception;

    /** Sends a call whose target is relative to the site. */
    Exchange send(String sitePath, RestCall call) throws Exception;

    @Override
    void close();

    /** The REST base of a setup term's taxonomy. */
    static String restBase(String taxonomy) {
        return switch (taxonomy) {
            case "category" -> "categories";
            case "post_tag" -> "tags";
            default -> throw new IllegalArgumentException("no REST base for taxonomy " + taxonomy);
        };
    }

    /** {@code call} with its target moved under {@code sitePath}. */
    static RestCall underSite(String sitePath, RestCall call) {
        String prefix = sitePath.endsWith("/") ? sitePath.substring(0, sitePath.length() - 1) : sitePath;
        return new RestCall(
                call.as(), call.method(), prefix + call.target(), call.headers(), call.contentType(), call.body());
    }

    /** The JSON body of a term step's REST request, with the parent's id when it has one. */
    static String termBody(SetupStep.Term term, Map<String, Long> refs) {
        ObjectNode body = OracleJson.MAPPER.createObjectNode().put("name", term.name());
        term.parentRef().ifPresent(parent -> body.put("parent", Objects.requireNonNull(refs.get(parent), parent)));
        return body.toString();
    }

    /** The WordPress side: {@link WordPressRestSite}'s REST API and its rest-setup.php operations. */
    static Side wordpress(WordPressRestSite site) {
        return new WordPress(site);
    }

    /** The WordPress side of the comparison. */
    final class WordPress implements Side {
        private final WordPressRestSite site;
        private final RestClient client;
        private final Map<String, Map<String, Long>> refsBySite = new HashMap<>();

        WordPress(WordPressRestSite site) {
            this.site = Objects.requireNonNull(site, "site");
            this.client = new RestClient(site.root(), RestClient.applicationPasswords(site));
        }

        @Override
        public ScenarioSite newSite(String scenarioId) throws IOException, InterruptedException {
            ObjectNode args = OracleJson.MAPPER.createObjectNode();
            args.put("path", "/" + scenarioId + "/");
            args.put("title", "Scenario " + scenarioId);
            JsonNode answer = site.operation("create_site", args);
            Map<String, Long> refs = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = answer.path("refs").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> ref = it.next();
                refs.put(ref.getKey(), ref.getValue().longValue());
            }
            ScenarioSite created = new ScenarioSite(answer.path("path").asText(), refs);
            refsBySite.put(created.path(), new HashMap<>(refs));
            return created;
        }

        @Override
        public Map<String, Long> setUp(String sitePath, List<SetupStep> steps) throws Exception {
            Map<String, Long> known = refsBySite.computeIfAbsent(sitePath, p -> new HashMap<>());
            Map<String, Long> created = new LinkedHashMap<>();
            for (SetupStep step : steps) {
                switch (step) {
                    case SetupStep.Post post -> {
                        ObjectNode args = OracleJson.MAPPER.createObjectNode();
                        args.put("site", sitePath)
                                .put("as", post.as())
                                .put("type", post.type())
                                .put("status", post.status())
                                .put("title", post.title());
                        created.put(
                                post.ref(),
                                site.operation("post", args).path("id").longValue());
                    }
                    case SetupStep.Term term -> {
                        // the rest_route form reaches the REST API whatever permalinks an earlier option step set
                        Exchange answer = send(
                                sitePath,
                                RestCall.json(
                                        term.as(),
                                        "POST",
                                        "/?rest_route=/wp/v2/" + restBase(term.taxonomy()),
                                        termBody(term, known)));
                        if (answer.status() != 201) {
                            throw new IllegalStateException(
                                    "WordPress setup term " + term + ": " + answer.status() + " " + answer.text());
                        }
                        created.put(term.ref(), answer.json().get("id").longValue());
                    }
                    case SetupStep.Option option -> {
                        ObjectNode args = OracleJson.MAPPER.createObjectNode();
                        args.put("site", sitePath).put("name", option.name());
                        args.set("value", option.value());
                        site.operation("option", args);
                    }
                }
                known.putAll(created);
            }
            return created;
        }

        @Override
        public Exchange send(String sitePath, RestCall call) throws IOException, InterruptedException {
            return client.send(underSite(sitePath, call));
        }

        @Override
        public void close() {
            site.close();
        }
    }
}
