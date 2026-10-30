package org.quarkpress.testing.wprestparity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.quarkpress.testing.wporacle.OracleJson;

/**
 * What WordPress answered to the verification list, kept for the controller and the later tasks. Each item maps to
 * the exchanges recorded for it, in the order they were made; {@link #save()} writes them, with the notes, to
 * {@code target/wp-rest-parity/part-d-facts.json} as {@code {"items": {item: [{request, status, headers, body,
 * bodyText}...]}, "notes": {item: {key: value}}}}. {@code body} is the parsed JSON when the body is JSON, else the text;
 * {@code bodyText} is always the body exactly as sent (escapes included). Header names are lower-case, each with its
 * lines in the order received.
 */
public final class PartDFacts {

    /** Where the facts and the OPTIONS bodies are written. */
    public static final Path DIRECTORY = Path.of("target", "wp-rest-parity");

    private static final ObjectNode ITEMS = OracleJson.MAPPER.createObjectNode();
    private static final ObjectNode NOTES = OracleJson.MAPPER.createObjectNode();

    private PartDFacts() {}

    /** Appends an exchange to the item's list. */
    public static synchronized void record(String item, Exchange exchange) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(exchange, "exchange");
        JsonNode list = ITEMS.get(item);
        ArrayNode exchanges = list == null ? ITEMS.putArray(item) : (ArrayNode) list;
        exchanges.add(toJson(exchange));
    }

    /** Records an observation about the item that is not an exchange (a count, a type, a reason). */
    public static synchronized void note(String item, String key, JsonNode value) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        JsonNode notes = NOTES.get(item);
        ObjectNode object = notes == null ? NOTES.putObject(item) : (ObjectNode) notes;
        object.set(key, value);
    }

    /**
     * Writes the items and notes recorded so far into {@code part-d-facts.json}, merging with what the file already
     * holds: an item recorded in this run replaces its earlier record, every other item is kept, so a run of a few
     * items never shrinks the file.
     */
    public static synchronized void save() {
        Path file = DIRECTORY.resolve("part-d-facts.json");
        JsonNode existing = null;
        if (Files.isRegularFile(file)) {
            try {
                existing = OracleJson.MAPPER.readTree(file.toFile());
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read the existing facts " + file, e);
            }
        }
        write(file, merge(existing, ITEMS, NOTES));
    }

    /**
     * The facts file after this run: the items of {@code existing} (a facts file's content, or null), with every item
     * recorded in this run replaced (or appended) and that item's notes replaced by this run's notes for it.
     */
    static ObjectNode merge(JsonNode existing, ObjectNode items, ObjectNode notes) {
        ObjectNode mergedItems = OracleJson.MAPPER.createObjectNode();
        ObjectNode mergedNotes = OracleJson.MAPPER.createObjectNode();
        if (existing != null && existing.path("items").isObject()) {
            mergedItems.setAll((ObjectNode) existing.get("items").deepCopy());
        }
        if (existing != null && existing.path("notes").isObject()) {
            existing.get("notes").properties().forEach(note -> {
                if (!items.has(note.getKey())) {
                    mergedNotes.set(note.getKey(), note.getValue().deepCopy());
                }
            });
        }
        items.properties()
                .forEach(item -> mergedItems.set(item.getKey(), item.getValue().deepCopy()));
        notes.properties()
                .forEach(note -> mergedNotes.set(note.getKey(), note.getValue().deepCopy()));
        ObjectNode facts = OracleJson.MAPPER.createObjectNode();
        facts.set("items", mergedItems);
        facts.set("notes", mergedNotes);
        return facts;
    }

    /**
     * Writes one OPTIONS exchange to {@code options/<route with / as _>.json}, in the same shape as an item's entry
     * ({@code /wp/v2/types} -> {@code _wp_v2_types.json}, {@code /} -> {@code _.json}).
     */
    public static void saveOptions(String route, Exchange exchange) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(exchange, "exchange");
        write(DIRECTORY.resolve("options").resolve(route.replace('/', '_') + ".json"), toJson(exchange));
    }

    static ObjectNode toJson(Exchange exchange) {
        RestCall call = exchange.call();
        ObjectNode entry = OracleJson.MAPPER.createObjectNode();
        ObjectNode request = entry.putObject("request");
        request.put("as", call.as());
        request.put("method", call.method());
        request.put("target", call.target());
        ObjectNode requestHeaders = request.putObject("headers");
        new TreeMap<>(call.headers()).forEach(requestHeaders::put);
        call.contentType().ifPresent(type -> request.put("contentType", type));
        call.body().ifPresent(body -> request.put("body", new String(body, StandardCharsets.UTF_8)));
        entry.put("status", exchange.status());
        ObjectNode headers = entry.putObject("headers");
        Map<String, List<String>> sorted = new TreeMap<>();
        exchange.headers()
                .forEach((name, lines) -> sorted.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .addAll(lines));
        sorted.forEach((name, lines) -> {
            ArrayNode values = headers.putArray(name);
            lines.forEach(values::add);
        });
        String text = exchange.text();
        JsonNode parsed = parse(exchange.body());
        if (parsed != null) {
            entry.set("body", parsed);
        } else {
            entry.put("body", text);
        }
        entry.put("bodyText", text);
        return entry;
    }

    private static JsonNode parse(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            // A body with anything after its JSON value (a PHP notice, say) is not JSON.
            JsonNode node = OracleJson.MAPPER
                    .reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
            return node == null || node.isMissingNode() ? null : node;
        } catch (IOException notJson) {
            return null;
        }
    }

    private static void write(Path file, JsonNode json) {
        try {
            Files.createDirectories(file.getParent());
            OracleJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), json);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
