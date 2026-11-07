package org.quarkpress.testing.wprestparity;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wprestparity.MediaScenario.Fixture;
import org.quarkpress.testing.wprestparity.MediaScenario.Setup;
import org.quarkpress.testing.wprestparity.MediaScenario.Step;

/**
 * Reads the media scenario format:
 *
 * <pre>{@code
 * {"id": "m09", "rule": "...", "editor": false,
 *  "setup": [{"post": "p:dated", "type": "post", "status": "publish", "date": "2019-03-14 12:00:00"},
 *            {"option": "qp_parity_no_unsupported_refusal", "value": "1"},
 *            {"folder": {"photo.jpg": {"fixture": "jpeg", "args": [16, 16]}}},
 *            {"network_option": "upload_filetypes", "value": "jpg jpeg png"}],
 *  "steps": [{"upload": "jpeg", "args": [64, 48], "as": "alice", "name": "m09.jpg", "type": "image/jpeg",
 *             "fields": {"generate_sub_sizes": "false", "post": "p:dated"}},
 *            {"update": "step1", "as": "alice", "fields": {"title": "New"}},
 *            {"process": "step1"}, {"delete": "step1"}, {"query": ["image", "video"]}]}
 * }</pre>
 *
 * {@code editor} defaults to false, {@code as} to alice, {@code args} to none and {@code fields} to none; field values
 * are strings. Unknown keys are refused, so a typo never silently drops a step.
 */
public final class MediaScenarioJson {

    private static final Set<String> SCENARIO_KEYS = Set.of("id", "rule", "editor", "setup", "steps");
    private static final Set<String> POST_KEYS = Set.of("post", "type", "status", "date");
    private static final Set<String> OPTION_KEYS = Set.of("option", "value");
    private static final Set<String> FOLDER_KEYS = Set.of("folder");
    private static final Set<String> NETWORK_OPTION_KEYS = Set.of("network_option", "value");
    private static final Set<String> FIXTURE_KEYS = Set.of("fixture", "args");
    private static final Set<String> UPLOAD_KEYS = Set.of("upload", "args", "as", "name", "type", "fields");
    private static final Set<String> UPDATE_KEYS = Set.of("update", "as", "fields");
    private static final Set<String> REF_STEP_KEYS = Set.of("as");
    private static final Set<String> QUERY_KEYS = Set.of("query", "as");

    private static final String DEFAULT_CALLER = "alice";

    private MediaScenarioJson() {}

    public static MediaScenario read(String json) {
        JsonNode root;
        try {
            root = OracleJson.MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("not a JSON media scenario: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("a media scenario is a JSON object");
        }
        keys(root, SCENARIO_KEYS, "scenario");
        String id = text(root, "id");
        boolean editor = false;
        if (root.has("editor")) {
            if (!root.get("editor").isBoolean()) {
                throw new IllegalArgumentException(id + ": editor is true or false");
            }
            editor = root.get("editor").booleanValue();
        }
        List<Setup> setup = new ArrayList<>();
        for (JsonNode entry : array(root, "setup", true)) {
            setup.add(setup(id, entry));
        }
        List<Step> steps = new ArrayList<>();
        for (JsonNode entry : array(root, "steps", false)) {
            steps.add(step(id, entry));
        }
        return new MediaScenario(id, text(root, "rule"), editor, setup, steps);
    }

    private static Setup setup(String id, JsonNode entry) {
        if (!entry.isObject()) {
            throw new IllegalArgumentException(id + ": a setup entry is an object: " + entry);
        }
        if (entry.has("post")) {
            keys(entry, POST_KEYS, id + " post");
            Optional<String> date = entry.has("date") ? Optional.of(text(entry, "date")) : Optional.empty();
            return new Setup.Post(text(entry, "post"), text(entry, "type"), text(entry, "status"), date);
        }
        if (entry.has("option")) {
            keys(entry, OPTION_KEYS, id + " option");
            return new Setup.Option(text(entry, "option"), required(entry, "value", id));
        }
        if (entry.has("folder")) {
            keys(entry, FOLDER_KEYS, id + " folder");
            JsonNode files = entry.get("folder");
            if (!files.isObject() || files.isEmpty()) {
                throw new IllegalArgumentException(id + ": a folder maps file names to fixtures");
            }
            Map<String, Fixture> fixtures = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = files.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> file = it.next();
                JsonNode spec = file.getValue();
                if (!spec.isObject()) {
                    throw new IllegalArgumentException(id + ": folder file " + file.getKey() + " is a fixture object");
                }
                keys(spec, FIXTURE_KEYS, id + " folder file " + file.getKey());
                fixtures.put(file.getKey(), new Fixture(text(spec, "fixture"), spec.get("args")));
            }
            return new Setup.Folder(fixtures);
        }
        if (entry.has("network_option")) {
            keys(entry, NETWORK_OPTION_KEYS, id + " network option");
            return new Setup.NetworkOption(text(entry, "network_option"), required(entry, "value", id));
        }
        throw new IllegalArgumentException(id + ": unknown setup entry " + entry);
    }

    private static Step step(String id, JsonNode entry) {
        if (!entry.isObject()) {
            throw new IllegalArgumentException(id + ": a step is an object: " + entry);
        }
        String as = entry.has("as") ? text(entry, "as") : DEFAULT_CALLER;
        if (!WordPressRestSite.USERS.contains(as)) {
            throw new IllegalArgumentException(id + ": unknown caller " + as);
        }
        if (entry.has("upload")) {
            keys(entry, UPLOAD_KEYS, id + " upload");
            return new Step.Upload(
                    as,
                    new Fixture(text(entry, "upload"), entry.get("args")),
                    text(entry, "name"),
                    text(entry, "type"),
                    fields(id, entry));
        }
        if (entry.has("update")) {
            keys(entry, UPDATE_KEYS, id + " update");
            return new Step.Update(as, text(entry, "update"), fields(id, entry));
        }
        if (entry.has("delete")) {
            keys(entry, union(REF_STEP_KEYS, "delete"), id + " delete");
            return new Step.Delete(as, text(entry, "delete"));
        }
        if (entry.has("process")) {
            keys(entry, union(REF_STEP_KEYS, "process"), id + " process");
            return new Step.Process(as, text(entry, "process"));
        }
        if (entry.has("query")) {
            keys(entry, QUERY_KEYS, id + " query");
            List<String> values = new ArrayList<>();
            for (JsonNode value : array(entry, "query", false)) {
                if (!value.isTextual()) {
                    throw new IllegalArgumentException(id + ": query values are strings: " + value);
                }
                values.add(value.textValue());
            }
            return new Step.Query(as, values);
        }
        throw new IllegalArgumentException(id + ": unknown step " + entry);
    }

    private static Map<String, String> fields(String id, JsonNode entry) {
        Map<String, String> fields = new LinkedHashMap<>();
        JsonNode node = entry.get("fields");
        if (node == null) {
            return fields;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(id + ": fields is an object: " + node);
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            if (!field.getValue().isTextual()) {
                throw new IllegalArgumentException(id + ": field " + field.getKey() + " is a string");
            }
            fields.put(field.getKey(), field.getValue().textValue());
        }
        return fields;
    }

    private static Set<String> union(Set<String> keys, String key) {
        Set<String> all = new java.util.HashSet<>(keys);
        all.add(key);
        return all;
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
            throw new IllegalArgumentException("'" + key + "' is a required string in " + node);
        }
        return value.textValue();
    }

    private static JsonNode required(JsonNode node, String key, String id) {
        JsonNode value = node.get(key);
        if (value == null) {
            throw new IllegalArgumentException(id + ": '" + key + "' is required in " + node);
        }
        return value;
    }

    private static Iterable<JsonNode> array(JsonNode node, String key, boolean optional) {
        JsonNode value = node.get(key);
        if (value == null && optional) {
            return List.of();
        }
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("'" + key + "' is a required array in " + node);
        }
        return value;
    }
}
