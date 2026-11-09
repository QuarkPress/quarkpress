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
 * {"id": "d03", "rule": "REST verification list item 3: ...",
 *  "setup": [{"term": "t:other", "as": "alice", "taxonomy": "category", "name": "d03-other", "parent": "t:x"},
 *            {"post": "p:draft", "as": "dave", "type": "post", "status": "draft", "title": "...",
 *             "fields": {"content": "...", "parent": "p:x", "categories": ["t:other"], "sticky": true}},
 *            {"revision": "r:v1", "as": "dave", "post": "p:draft", "title": "...", "content": "..."},
 *            {"autosave": "r:auto", "as": "bob", "post": "p:draft", "title": "...", "content": "..."},
 *            {"draft": "p:new", "as": "carol", "type": "post"},
 *            {"lock": "p:draft", "user": "dave", "age": 10}, {"own_site": "dave"},
 *            {"option": "permalink_structure", "value": ""}, {"leave": "bob"},
 *            {"media": "m:photo", "as": "carol", "fixture": {"fixture": "jpeg", "args": [64, 48]},
 *             "name": "photo.jpg", "type": "image/jpeg", "fields": {"post": "p:draft", "alt_text": "..."}}],
 *  "requests": [{"as": "alice", "method": "GET", "target": "/wp-json/wp/v2/users/{u:alice}",
 *                "headers": {"Accept": "application/json"}, "contentType": "application/json", "body": "{}"},
 *               {"as": "carol", "method": "POST", "target": "/wp-json/wp/v2/media",
 *                "upload": {"fixture": {"fixture": "png", "args": [8, 8]}, "name": "dot.png", "type": "image/png",
 *                           "fields": {"post": "{p:draft}"}}},
 *               {"as": "carol", "method": "POST", "target": "/wp-json/wp/v2/media", "contentType": "image/jpeg",
 *                "headerLines": {"Content-Disposition": ["attachment; filename=a.jpg"]},
 *                "raw": {"fixture": {"fixture": "jpeg", "args": [16, 16]}}},
 *               {"as": "alice", "method": "GET", "target": "/wp-json/wp/v2/media/{m:photo}", "https": true}]}
 * }</pre>
 *
 * {@code "editor": true} marks a scenario that needs an image editor on WordPress's side ({@link
 * RestScenario#editor}). Targets are relative to the scenario's site. Unknown keys are refused, so a typo never
 * silently drops a step.
 */
public final class RestScenarioJson {

    private static final Set<String> SCENARIO_KEYS = Set.of("id", "rule", "editor", "setup", "requests");
    private static final Set<String> POST_KEYS = Set.of("post", "as", "type", "status", "title", "fields");
    private static final Set<String> REVISION_KEYS = Set.of("revision", "as", "post", "title", "content");
    private static final Set<String> AUTOSAVE_KEYS = Set.of("autosave", "as", "post", "title", "content");
    private static final Set<String> DRAFT_KEYS = Set.of("draft", "as", "type");
    private static final Set<String> LOCK_KEYS = Set.of("lock", "user", "age");
    private static final Set<String> OWN_SITE_KEYS = Set.of("own_site");
    private static final Set<String> TERM_KEYS = Set.of("term", "as", "taxonomy", "name", "parent");
    private static final Set<String> OPTION_KEYS = Set.of("option", "value");
    private static final Set<String> LEAVE_KEYS = Set.of("leave");
    private static final Set<String> MEDIA_KEYS = Set.of("media", "as", "fixture", "name", "type", "fields");
    private static final Set<String> FIXTURE_KEYS = Set.of("fixture", "args");
    private static final Set<String> REQUEST_KEYS =
            Set.of("as", "method", "target", "headers", "headerLines", "contentType", "body", "upload", "raw", "https");
    private static final Set<String> UPLOAD_KEYS = Set.of("fixture", "name", "type", "fields");
    private static final Set<String> RAW_KEYS = Set.of("fixture");

    /** A request as read: its call and its form. */
    private record Request(RestCall call, RestScenario.Form form) {}

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
        boolean editor = false;
        if (root.has("editor")) {
            if (!root.get("editor").isBoolean()) {
                throw new IllegalArgumentException(id + ": editor is true or false");
            }
            editor = root.get("editor").booleanValue();
        }
        List<SetupStep> setup = new ArrayList<>();
        for (JsonNode step : array(root, "setup", true)) {
            setup.add(step(id, step));
        }
        List<RestCall> requests = new ArrayList<>();
        List<RestScenario.Form> forms = new ArrayList<>();
        for (JsonNode node : array(root, "requests", false)) {
            Request request = request(id, node);
            requests.add(request.call());
            forms.add(request.form());
        }
        return new RestScenario(id, text(root, "rule"), editor, setup, requests, forms);
    }

    public static String write(RestScenario scenario) {
        ObjectNode root = OracleJson.MAPPER.createObjectNode();
        root.put("id", scenario.id());
        root.put("rule", scenario.rule());
        if (scenario.editor()) {
            root.put("editor", true);
        }
        ArrayNode setup = root.putArray("setup");
        for (SetupStep step : scenario.setup()) {
            ObjectNode node = setup.addObject();
            switch (step) {
                case SetupStep.Post post -> {
                    node.put("post", post.ref())
                            .put("as", post.as())
                            .put("type", post.type())
                            .put("status", post.status())
                            .put("title", post.title());
                    if (!post.fields().isEmpty()) {
                        node.set("fields", post.fields());
                    }
                }
                case SetupStep.Revision revision ->
                    node.put("revision", revision.ref())
                            .put("as", revision.as())
                            .put("post", revision.postRef())
                            .put("title", revision.title())
                            .put("content", revision.content());
                case SetupStep.Autosave autosave ->
                    node.put("autosave", autosave.ref())
                            .put("as", autosave.as())
                            .put("post", autosave.postRef())
                            .put("title", autosave.title())
                            .put("content", autosave.content());
                case SetupStep.Draft draft ->
                    node.put("draft", draft.ref()).put("as", draft.as()).put("type", draft.type());
                case SetupStep.Lock lock ->
                    node.put("lock", lock.postRef()).put("user", lock.user()).put("age", lock.ageSeconds());
                case SetupStep.OwnSite ownSite -> node.put("own_site", ownSite.user());
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
                case SetupStep.Leave leave -> node.put("leave", leave.user());
                case SetupStep.Media media -> {
                    node.put("media", media.ref()).put("as", media.as());
                    node.set("fixture", fixture(media.fixture()));
                    node.put("name", media.name()).put("type", media.type());
                    if (!media.fields().isEmpty()) {
                        node.set("fields", media.fields());
                    }
                }
            }
        }
        ArrayNode requests = root.putArray("requests");
        for (int i = 0; i < scenario.requests().size(); i++) {
            RestCall call = scenario.requests().get(i);
            RestScenario.Form form = scenario.forms().get(i);
            ObjectNode node = requests.addObject();
            node.put("as", call.as()).put("method", call.method()).put("target", call.target());
            if (!call.headers().isEmpty()) {
                ObjectNode headers = node.putObject("headers");
                call.headers().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(header -> headers.put(header.getKey(), header.getValue()));
            }
            if (!call.headerLines().isEmpty()) {
                ObjectNode lines = node.putObject("headerLines");
                call.headerLines().forEach((name, values) -> {
                    ArrayNode array = lines.putArray(name);
                    values.forEach(array::add);
                });
            }
            call.contentType().ifPresent(type -> node.put("contentType", type));
            call.body().ifPresent(body -> node.put("body", new String(body, StandardCharsets.UTF_8)));
            form.upload().ifPresent(upload -> {
                ObjectNode out = node.putObject("upload");
                out.set("fixture", fixture(upload.fixture()));
                out.put("name", upload.name()).put("type", upload.type());
                if (!upload.fields().isEmpty()) {
                    ObjectNode fields = out.putObject("fields");
                    upload.fields().forEach(fields::put);
                }
            });
            form.raw().ifPresent(raw -> node.putObject("raw").set("fixture", fixture(raw)));
            if (form.https()) {
                node.put("https", true);
            }
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
        if (step.has("post") && !step.has("revision") && !step.has("autosave")) {
            keys(step, POST_KEYS, id + " post step");
            ObjectNode fields = null;
            if (step.has("fields")) {
                if (!step.get("fields").isObject()) {
                    throw new IllegalArgumentException(id + ": a post step's fields is an object");
                }
                fields = (ObjectNode) step.get("fields");
            }
            return new SetupStep.Post(
                    text(step, "post"),
                    text(step, "as"),
                    text(step, "type"),
                    text(step, "status"),
                    text(step, "title"),
                    fields);
        }
        if (step.has("revision")) {
            keys(step, REVISION_KEYS, id + " revision step");
            return new SetupStep.Revision(
                    text(step, "revision"),
                    text(step, "as"),
                    text(step, "post"),
                    text(step, "title"),
                    text(step, "content"));
        }
        if (step.has("autosave")) {
            keys(step, AUTOSAVE_KEYS, id + " autosave step");
            return new SetupStep.Autosave(
                    text(step, "autosave"),
                    text(step, "as"),
                    text(step, "post"),
                    text(step, "title"),
                    text(step, "content"));
        }
        if (step.has("draft")) {
            keys(step, DRAFT_KEYS, id + " draft step");
            return new SetupStep.Draft(text(step, "draft"), text(step, "as"), text(step, "type"));
        }
        if (step.has("lock")) {
            keys(step, LOCK_KEYS, id + " lock step");
            JsonNode age = step.get("age");
            if (age == null || !age.isIntegralNumber()) {
                throw new IllegalArgumentException(id + ": a lock step's age is an integer (seconds)");
            }
            return new SetupStep.Lock(text(step, "lock"), text(step, "user"), age.intValue());
        }
        if (step.has("own_site")) {
            keys(step, OWN_SITE_KEYS, id + " own_site step");
            return new SetupStep.OwnSite(text(step, "own_site"));
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
        if (step.has("leave")) {
            keys(step, LEAVE_KEYS, id + " leave step");
            return new SetupStep.Leave(text(step, "leave"));
        }
        if (step.has("media")) {
            keys(step, MEDIA_KEYS, id + " media step");
            ObjectNode fields = null;
            if (step.has("fields")) {
                if (!step.get("fields").isObject()) {
                    throw new IllegalArgumentException(id + ": a media step's fields is an object");
                }
                fields = (ObjectNode) step.get("fields");
            }
            return new SetupStep.Media(
                    text(step, "media"),
                    text(step, "as"),
                    fixture(id, step.get("fixture")),
                    text(step, "name"),
                    text(step, "type"),
                    fields);
        }
        throw new IllegalArgumentException(
                id + ": a setup step is a post, a term, a revision, an autosave, a draft, a lock, an own_site, an"
                        + " option, a leave or a media: " + step);
    }

    /** A fixture as JSON: {@code {"fixture": <name>, "args": ...}}, without {@code args} when it has none. */
    private static ObjectNode fixture(MediaScenario.Fixture fixture) {
        ObjectNode node = OracleJson.MAPPER.createObjectNode().put("fixture", fixture.name());
        if (!fixture.args().isNull() && !fixture.args().isMissingNode()) {
            node.set("args", fixture.args());
        }
        return node;
    }

    private static MediaScenario.Fixture fixture(String id, JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(id + ": a fixture is an object {\"fixture\": ..., \"args\": ...}");
        }
        keys(node, FIXTURE_KEYS, id + " fixture");
        return new MediaScenario.Fixture(text(node, "fixture"), node.get("args"));
    }

    private static Request request(String id, JsonNode request) {
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
        Map<String, List<String>> lines = new LinkedHashMap<>();
        if (request.has("headerLines")) {
            JsonNode node = request.get("headerLines");
            if (!node.isObject()) {
                throw new IllegalArgumentException(id + ": headerLines is an object of string lists");
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> line = it.next();
                List<String> values = new ArrayList<>();
                if (!line.getValue().isArray()) {
                    throw new IllegalArgumentException(id + ": header lines " + line.getKey() + " are a list");
                }
                for (JsonNode value : line.getValue()) {
                    if (!value.isTextual()) {
                        throw new IllegalArgumentException(id + ": a header line of " + line.getKey() + " is a string");
                    }
                    values.add(value.textValue());
                }
                lines.put(line.getKey(), values);
            }
        }
        String method = text(request, "method");
        if (!List.of("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            throw new IllegalArgumentException(id + ": unknown method " + method);
        }
        Optional<RestScenario.Upload> upload = Optional.empty();
        if (request.has("upload")) {
            JsonNode node = request.get("upload");
            if (!node.isObject()) {
                throw new IllegalArgumentException(id + ": an upload is an object");
            }
            keys(node, UPLOAD_KEYS, id + " upload");
            Map<String, String> fields = new LinkedHashMap<>();
            if (node.has("fields")) {
                if (!node.get("fields").isObject()) {
                    throw new IllegalArgumentException(id + ": an upload's fields is an object");
                }
                for (Iterator<Map.Entry<String, JsonNode>> it =
                                node.get("fields").fields();
                        it.hasNext(); ) {
                    Map.Entry<String, JsonNode> field = it.next();
                    if (!field.getValue().isTextual()) {
                        throw new IllegalArgumentException(
                                id + ": the upload field " + field.getKey() + " is a string");
                    }
                    fields.put(field.getKey(), field.getValue().textValue());
                }
            }
            upload = Optional.of(new RestScenario.Upload(
                    fixture(id, node.get("fixture")), text(node, "name"), text(node, "type"), fields));
        }
        Optional<MediaScenario.Fixture> raw = Optional.empty();
        if (request.has("raw")) {
            JsonNode node = request.get("raw");
            if (!node.isObject()) {
                throw new IllegalArgumentException(id + ": a raw body is an object {\"fixture\": ...}");
            }
            keys(node, RAW_KEYS, id + " raw");
            raw = Optional.of(fixture(id, node.get("fixture")));
        }
        if (upload.isPresent() && raw.isPresent()) {
            throw new IllegalArgumentException(id + ": a request is an upload or a raw body, not both");
        }
        boolean https = false;
        if (request.has("https")) {
            if (!request.get("https").isBoolean()) {
                throw new IllegalArgumentException(id + ": https is true or false");
            }
            https = request.get("https").booleanValue();
        }
        RestCall call =
                new RestCall(text(request, "as"), method, text(request, "target"), headers, contentType, body, lines);
        return new Request(call, new RestScenario.Form(upload, raw, https));
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
