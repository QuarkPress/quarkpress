package org.quarkpress.testing.wprestparity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wprestparity.RestScenario.SetupStep;

/**
 * Reads and writes the scenario format:
 *
 * <pre>{@code
 * {"id": "d03", "rule": "Part D 3: ...",
 *  "setup": [{"term": "t:other", "as": "alice", "taxonomy": "category", "name": "d03-other", "parent": "t:x"},
 *            {"post": "p:draft", "as": "dave", "type": "post", "status": "draft", "title": "..."},
 *            {"option": "permalink_structure", "value": ""}],
 *  "requests": [{"as": "alice", "method": "GET", "target": "/wp-json/wp/v2/users/{u:alice}",
 *                "headers": {"Accept": "application/json"}, "contentType": "application/json", "body": "{}"}]}
 * }</pre>
 *
 * Targets are relative to the scenario's site. Unknown keys are refused, so a typo never silently drops a step.
 */
public final class RestScenarioJson {

    private static final Set<String> SCENARIO_KEYS = Set.of("id", "rule", "setup", "requests");
    private static final Set<String> POST_KEYS = Set.of("post", "as", "type", "status", "title");
    private static final Set<String> TERM_KEYS = Set.of("term", "as", "taxonomy", "name", "parent");
    private static final Set<String> OPTION_KEYS = Set.of("option", "value");
    private static final Set<String> REQUEST_KEYS = Set.of("as", "method", "target", "headers", "contentType", "body");

    private RestScenarioJson() {}

    public static RestScenario read(String json) {
        JsonNode root;
        try {
            root = OracleJson.MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("not a JSON scenario: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("a scenario is a JSON object");
        }
        keys(root, SCENARIO_KEYS, "scenario");
        String id = text(root, "id");
        List<SetupStep> setup = new ArrayList<>();
        for (JsonNode step : array(root, "setup", true)) {
            setup.add(step(id, step));
        }
        List<RestCall> requests = new ArrayList<>();
        for (JsonNode request : array(root, "requests", false)) {
            requests.add(request(id, request));
        }
        return new RestScenario(id, text(root, "rule"), setup, requests);
    }

    public static String write(RestScenario scenario) {
        ObjectNode root = OracleJson.MAPPER.createObjectNode();
        root.put("id", scenario.id());
        root.put("rule", scenario.rule());
        ArrayNode setup = root.putArray("setup");
        for (SetupStep step : scenario.setup()) {
            ObjectNode node = setup.addObject();
            switch (step) {
                case SetupStep.Post post ->
                    node.put("post", post.ref())
                            .put("as", post.as())
                            .put("type", post.type())
                            .put("status", post.status())
                            .put("title", post.title());
                case SetupStep.Term term -> {
                    node.put("term", term.ref())
                            .put("as", term.as())
                            .put("taxonomy", term.taxonomy())
                            .put("name", term.name());
                    term.parentRef().ifPresent(parent -> node.put("parent", parent));
                }
                case SetupStep.Option option -> {
                    node.put("option", option.name());
                    node.set("value", option.value());
                }
            }
        }
        ArrayNode requests = root.putArray("requests");
        for (RestCall call : scenario.requests()) {
            ObjectNode node = requests.addObject();
            node.put("as", call.as()).put("method", call.method()).put("target", call.target());
            if (!call.headers().isEmpty()) {
                ObjectNode headers = node.putObject("headers");
                call.headers().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(header -> headers.put(header.getKey(), header.getValue()));
            }
            call.contentType().ifPresent(type -> node.put("contentType", type));
            call.body().ifPresent(body -> node.put("body", new String(body, StandardCharsets.UTF_8)));
        }
        try {
            return OracleJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SetupStep step(String id, JsonNode step) {
        if (!step.isObject()) {
            throw new IllegalArgumentException(id + ": a setup step is an object");
        }
        if (step.has("post")) {
            keys(step, POST_KEYS, id + " post step");
            return new SetupStep.Post(
                    text(step, "post"),
                    text(step, "as"),
                    text(step, "type"),
                    text(step, "status"),
                    text(step, "title"));
        }
        if (step.has("term")) {
            keys(step, TERM_KEYS, id + " term step");
            Optional<String> parent = step.has("parent") ? Optional.of(text(step, "parent")) : Optional.empty();
            return new SetupStep.Term(
                    text(step, "term"), text(step, "as"), text(step, "taxonomy"), text(step, "name"), parent);
        }
        if (step.has("option")) {
            keys(step, OPTION_KEYS, id + " option step");
            if (!step.has("value")) {
                throw new IllegalArgumentException(id + ": an option step needs a value");
            }
            return new SetupStep.Option(text(step, "option"), step.get("value"));
        }
        throw new IllegalArgumentException(id + ": a setup step is a post, a term or an option: " + step);
    }

    private static RestCall request(String id, JsonNode request) {
        if (!request.isObject()) {
            throw new IllegalArgumentException(id + ": a request is an object");
        }
        keys(request, REQUEST_KEYS, id + " request");
        Map<String, String> headers = new LinkedHashMap<>();
        if (request.has("headers")) {
            JsonNode node = request.get("headers");
            if (!node.isObject()) {
                throw new IllegalArgumentException(id + ": headers is an object");
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> header = it.next();
                if (!header.getValue().isTextual()) {
                    throw new IllegalArgumentException(id + ": header " + header.getKey() + " is not a string");
                }
                headers.put(header.getKey(), header.getValue().textValue());
            }
        }
        Optional<String> contentType =
                request.has("contentType") ? Optional.of(text(request, "contentType")) : Optional.empty();
        Optional<byte[]> body = request.has("body")
                ? Optional.of(text(request, "body").getBytes(StandardCharsets.UTF_8))
                : Optional.empty();
        String method = text(request, "method");
        if (!List.of("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            throw new IllegalArgumentException(id + ": unknown method " + method);
        }
        return new RestCall(text(request, "as"), method, text(request, "target"), headers, contentType, body);
    }

    private static void keys(JsonNode node, Set<String> allowed, String what) {
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(what + ": unknown key " + key);
            }
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("'" + key + "' must be a string in " + node);
        }
        return value.textValue();
    }

    private static List<JsonNode> array(JsonNode node, String key, boolean optional) {
        JsonNode value = node.get(key);
        if (value == null && optional) {
            return List.of();
        }
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("'" + key + "' must be an array");
        }
        List<JsonNode> out = new ArrayList<>();
        value.forEach(out::add);
        return out;
    }
}
