package org.quarkpress.testing.wpscenario;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.quarkpress.testing.wporacle.OracleJson;

/**
 * The scenario format: {@code {"id", "rule", "variant", "ops": [...]}} with ops {@code create_site {domain, path,
 * title}}, {@code lookup {host, path}}, {@code set_flag {site, flag, value}} ({@code flag} one of archived, mature,
 * spam, deleted, public) and {@code read_options {site, names}} ({@code names} a non-empty list of distinct option
 * names). Unknown keys, duplicate keys and trailing content are refused.
 */
public final class ScenarioJson {

    /** A copy of the shared mapper that reads strictly: no trailing tokens, no duplicate keys. */
    private static final ObjectMapper STRICT_READER = OracleJson.MAPPER
            .copy()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private ScenarioJson() {}

    public static Scenario parse(String json) {
        JsonNode node;
        try {
            node = STRICT_READER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("scenario JSON is not valid: " + e.getOriginalMessage(), e);
        }
        return parse(node, false);
    }

    public static Scenario parse(JsonNode node, boolean generated) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("a scenario must be a JSON object");
        }
        String id = text(node, "id", "(no id)");
        onlyKeys(node, id, "id", "rule", "variant", "ops");
        List<ScenarioOp> ops = new ArrayList<>();
        JsonNode list = node.path("ops");
        if (!list.isArray()) {
            throw new IllegalArgumentException("scenario " + id + ": 'ops' must be a list");
        }
        for (JsonNode op : list) {
            ops.add(op(op, id));
        }
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("scenario " + id + " has no ops");
        }
        return new Scenario(
                id, text(node, "rule", id), NetworkVariant.named(text(node, "variant", id)), ops, generated);
    }

    public static ObjectNode toJson(Scenario scenario) {
        ObjectNode node = OracleJson.MAPPER.createObjectNode();
        node.put("id", scenario.id());
        node.put("rule", scenario.rule());
        node.put("variant", scenario.variant().name());
        ArrayNode ops = node.putArray("ops");
        for (ScenarioOp op : scenario.ops()) {
            ObjectNode out = ops.addObject().put("op", op.kind());
            switch (op) {
                case ScenarioOp.CreateSite create ->
                    out.put("domain", create.domain())
                            .put("path", create.path())
                            .put("title", create.title());
                case ScenarioOp.Lookup lookup -> out.put("host", lookup.host()).put("path", lookup.path());
                case ScenarioOp.SetFlag flag ->
                    out.put("site", flag.site())
                            .put("flag", flag.flag().wordpressName())
                            .put("value", flag.value());
                case ScenarioOp.ReadOptions read -> {
                    out.put("site", read.site());
                    ArrayNode names = out.putArray("names");
                    read.names().forEach(names::add);
                }
            }
        }
        return node;
    }

    public static String write(Scenario scenario) {
        try {
            return OracleJson.MAPPER.writeValueAsString(toJson(scenario));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The variant as the PHP driver reads it. */
    public static ObjectNode variantJson(NetworkVariant variant) {
        return OracleJson.MAPPER
                .createObjectNode()
                .put("name", variant.name())
                .put("domain", variant.domain())
                .put("path", variant.path())
                .put("subdomain_install", variant.subdomainInstall())
                .put("path_segments", variant.pathSegments());
    }

    private static ScenarioOp op(JsonNode op, String id) {
        if (!op.isObject()) {
            throw new IllegalArgumentException("scenario " + id + ": every op must be an object");
        }
        String kind = text(op, "op", id);
        switch (kind) {
            case "create_site" -> onlyKeys(op, id, "op", "domain", "path", "title");
            case "lookup" -> onlyKeys(op, id, "op", "host", "path");
            case "set_flag" -> onlyKeys(op, id, "op", "site", "flag", "value");
            case "read_options" -> onlyKeys(op, id, "op", "site", "names");
            default -> {}
        }
        return switch (kind) {
            case "create_site" ->
                new ScenarioOp.CreateSite(text(op, "domain", id), text(op, "path", id), text(op, "title", id));
            case "lookup" -> new ScenarioOp.Lookup(text(op, "host", id), text(op, "path", id));
            case "set_flag" ->
                new ScenarioOp.SetFlag(
                        number(op, "site", id), ScenarioOp.Flag.named(text(op, "flag", id)), bool(op, "value", id));
            case "read_options" -> {
                JsonNode list = op.get("names");
                if (list == null || !list.isArray() || list.isEmpty()) {
                    throw new IllegalArgumentException("scenario " + id + ": 'names' must be a non-empty list");
                }
                List<String> names = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (JsonNode name : list) {
                    if (!name.isTextual()) {
                        throw new IllegalArgumentException("scenario " + id + ": 'names' holds only text");
                    }
                    if (!seen.add(name.textValue())) {
                        throw new IllegalArgumentException(
                                "scenario " + id + ": 'names' lists '" + name.textValue() + "' twice");
                    }
                    names.add(name.textValue());
                }
                yield new ScenarioOp.ReadOptions(number(op, "site", id), names);
            }
            default -> throw new IllegalArgumentException("scenario " + id + ": unknown op '" + kind + "'");
        };
    }

    private static void onlyKeys(JsonNode node, String id, String... allowed) {
        List<String> known = List.of(allowed);
        for (java.util.Iterator<String> keys = node.fieldNames(); keys.hasNext(); ) {
            String key = keys.next();
            if (!known.contains(key)) {
                throw new IllegalArgumentException(
                        "scenario " + id + ": unknown key '" + key + "'; known: " + String.join(", ", known));
            }
        }
    }

    private static String text(JsonNode node, String field, String id) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("scenario " + id + ": '" + field + "' must be text");
        }
        return value.textValue();
    }

    private static int number(JsonNode node, String field, String id) {
        JsonNode value = node.get(field);
        if (value == null || !value.isInt()) {
            throw new IllegalArgumentException("scenario " + id + ": '" + field + "' must be a whole number");
        }
        return value.intValue();
    }

    private static boolean bool(JsonNode node, String field, String id) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException("scenario " + id + ": '" + field + "' must be true or false");
        }
        return value.booleanValue();
    }
}
