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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.quarkpress.testing.wporacle.OracleJson;

/**
 * What WordPress answered to the verification list, kept as the reference the scenarios and the tests were written
 * from. Each item maps to the exchanges recorded for it, in the order they were made; {@link #save()} writes them, with
 * the notes, to {@code target/wp-rest-parity/verification-facts.json} as {@code {"items": {item: [{request, status,
 * headers, body, bodyText}...]}, "notes": {item: {key: value}}}}. {@code body} is the parsed JSON when the body is
 * JSON, else the text; {@code bodyText} is always the body exactly as sent (escapes included). Header names are
 * lower-case, each with its lines in the order received. A {@code text/html} answer (an admin or front-end page) is
 * kept whole only under {@code target/wp-rest-parity/pages/<item>-<n>.html}; its entry holds, instead of the text, a
 * summary: the page's path, byte length and SHA-256, its {@code <title>}, the {@code <body>} class, the first 4 KB and
 * the fragments the items read ({@code postLock}, {@code initializeEditor}, {@code <time>} tags, the wp_die message),
 * and no {@code bodyText}.
 */
public final class VerificationFacts {

    /** Where the facts and the OPTIONS bodies are written. */
    public static final Path DIRECTORY = Path.of("target", "wp-rest-parity");

    private static final ObjectNode ITEMS = OracleJson.MAPPER.createObjectNode();
    private static final ObjectNode NOTES = OracleJson.MAPPER.createObjectNode();

    private VerificationFacts() {}

    /** Appends an exchange to the item's list. */
    public static synchronized void record(String item, Exchange exchange) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(exchange, "exchange");
        JsonNode list = ITEMS.get(item);
        ArrayNode exchanges = list == null ? ITEMS.putArray(item) : (ArrayNode) list;
        ObjectNode entry = toJson(exchange);
        if (isHtml(exchange) && exchange.body().length > 0) {
            Path page = DIRECTORY.resolve("pages").resolve(item + "-" + exchanges.size() + ".html");
            writeBytes(page, exchange.body());
            entry.set("body", htmlSummary(exchange.text(), exchange.body(), page));
            entry.remove("bodyText");
        }
        exchanges.add(entry);
    }

    static boolean isHtml(Exchange exchange) {
        return exchange.header("Content-Type")
                .map(type -> type.startsWith("text/html"))
                .orElse(false);
    }

    /** The summary that stands in for an HTML page in the facts (see the class comment). */
    static ObjectNode htmlSummary(String text, byte[] bytes, Path page) {
        ObjectNode summary = OracleJson.MAPPER.createObjectNode();
        summary.put("page", DIRECTORY.relativize(page).toString());
        summary.put("length", bytes.length);
        summary.put("sha256", sha256(bytes));
        summary.put("title", between(text, "<title>", "</title>"));
        summary.put("bodyClass", bodyClass(text));
        summary.put("head", text.substring(0, Math.min(text.length(), 4096)));
        ObjectNode fragments = summary.putObject("fragments");
        fragment(fragments, "postLock", text, "\"postLock\"", 400);
        fragment(fragments, "initializeEditor", text, "initializeEditor", 300);
        fragment(fragments, "wpDie", text, "class=\"wp-die-message\"", 400);
        ArrayNode times = OracleJson.MAPPER.createArrayNode();
        int from = 0;
        while (times.size() < 20) {
            int at = text.indexOf("<time", from);
            int end = at < 0 ? -1 : text.indexOf("</time>", at);
            if (end < 0) {
                break;
            }
            times.add(text.substring(at, end + "</time>".length()));
            from = end + "</time>".length();
        }
        if (!times.isEmpty()) {
            fragments.set("time", times);
        }
        return summary;
    }

    private static void fragment(ObjectNode fragments, String key, String text, String marker, int length) {
        int at = text.indexOf(marker);
        if (at >= 0) {
            fragments.put(key, text.substring(at, Math.min(text.length(), at + length)));
        }
    }

    private static String between(String text, String open, String close) {
        int at = text.indexOf(open);
        int end = at < 0 ? -1 : text.indexOf(close, at + open.length());
        return end < 0 ? "" : text.substring(at + open.length(), end);
    }

    private static String bodyClass(String text) {
        int body = text.indexOf("<body");
        int end = body < 0 ? -1 : text.indexOf('>', body);
        if (end < 0) {
            return "";
        }
        String tag = text.substring(body, end);
        int at = tag.indexOf("class=\"");
        if (at < 0) {
            return "";
        }
        int close = tag.indexOf('"', at + "class=\"".length());
        return close < 0 ? "" : tag.substring(at + "class=\"".length(), close);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeBytes(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
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
     * Writes the items and notes recorded so far into {@code verification-facts.json}, merging with what the file
     * already holds: an item recorded in this run replaces its earlier record, every other item is kept, so a run of a
     * few items never shrinks the file.
     */
    public static synchronized void save() {
        Path file = DIRECTORY.resolve("verification-facts.json");
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
