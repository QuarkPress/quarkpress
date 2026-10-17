package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The reference examples in src/test/resources/examples. Each "expected" value is WordPress's own result
 * (written by src/test/php/kses-examples.php), so a failing example means the Java code is wrong.
 */
final class Examples {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path DIR = Path.of("src/test/resources/examples");
    static final List<String> FILES = List.of(
            "no-null.json",
            "text-utils.json",
            "decode-attribute.json",
            "normalize.json",
            "escape.json",
            "bad-protocol.json",
            "hair.json",
            "safecss.json",
            "kses-bare.json",
            "kses-wp.json",
            "one-attr.json",
            "strip.json");

    /** The upload URL the oracle driver's wp_upload_dir() returns (kses-env.php). */
    static final String UPLOAD_URL = "https://example.org/wp-content/uploads/2026/10";

    private Examples() {}

    static List<JsonNode> load(String file) {
        try {
            List<JsonNode> examples = new ArrayList<>();
            JSON.readTree(DIR.resolve(file).toFile()).forEach(examples::add);
            return examples;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Asserts that QuarkPress gives WordPress's result for every example in {@code file}. */
    static void assertFile(String file) {
        List<JsonNode> examples = load(file);
        List<String> failures = new ArrayList<>();
        for (JsonNode example : examples) {
            JsonNode actual;
            try {
                actual = run(example);
            } catch (RuntimeException e) {
                actual = TextNode.valueOf("threw " + e);
            }
            if (!actual.equals(example.get("expected"))) {
                failures.add(example.get("id").asText() + "  input " + example.get("text")
                        + "\n    expected " + example.get("expected")
                        + "\n    actual   " + actual);
            }
        }
        assertThat(examples).as(file).isNotEmpty();
        assertThat(failures).as(file + ": QuarkPress differs from WordPress").isEmpty();
    }

    /** QuarkPress's result for one example, in the JSON shape of WordPress's result. */
    static JsonNode run(JsonNode e) {
        String fn = e.get("fn").asText();
        String text = e.path("text").asText();
        switch (fn) {
            case "no_null" -> {
                return str(NullFilter.clean(text, e.get("keep").asBoolean()));
            }
            case "stripslashes" -> {
                return str(TextUtils.stripSlashes(text));
            }
            case "html_error" -> {
                return str(TextUtils.htmlError(text));
            }
            case "decode_attribute" -> {
                return str(CharacterReferences.decodeAttribute(text));
            }
            case "normalize_entities" -> {
                return str(EntityNormalizer.normalize(text, e.get("xml").asBoolean()));
            }
            case "esc_attr" -> {
                return str(HtmlEscaper.escape(text));
            }
            case "bad_protocol" -> {
                List<String> protocols = strings(e.get("protocols"));
                return str(ProtocolFilter.filter(
                        text, !e.has("protocols") ? WordPressTables.INSTANCE.protocols() : protocols));
            }
            // WIRED-CASES
            default -> throw new UnsupportedOperationException("no QuarkPress function is wired for " + fn);
        }
    }

    static TextNode str(String s) {
        return TextNode.valueOf(s);
    }

    /** A JSON list of strings; empty when absent or null. */
    static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && !node.isNull()) {
            node.forEach(item -> out.add(item.asText()));
        }
        return out;
    }
}
