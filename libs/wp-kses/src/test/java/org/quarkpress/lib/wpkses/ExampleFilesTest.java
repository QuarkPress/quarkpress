package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExampleFilesTest {

    private static final Set<String> FUNCTIONS = Set.of(
            "no_null",
            "stripslashes",
            "html_error",
            "decode_attribute",
            "normalize_entities",
            "esc_attr",
            "bad_protocol",
            "hair",
            "safecss",
            "kses",
            "one_attr",
            "strip_all_tags");

    /** Written by src/test/php/kses-examples.php against WordPress 7.1.3. */
    private static final Map<String, Integer> COUNTS = Map.ofEntries(
            Map.entry("no-null.json", 7),
            Map.entry("text-utils.json", 12),
            Map.entry("decode-attribute.json", 37),
            Map.entry("normalize.json", 24),
            Map.entry("escape.json", 6),
            Map.entry("bad-protocol.json", 64),
            Map.entry("hair.json", 14),
            Map.entry("safecss.json", 117),
            Map.entry("kses-bare.json", 292),
            Map.entry("kses-wp.json", 54),
            Map.entry("one-attr.json", 42),
            Map.entry("strip.json", 48));

    @Test
    void everyFileIsWellFormed() {
        assertThat(Examples.FILES).containsExactlyInAnyOrderElementsOf(COUNTS.keySet());
        for (String file : Examples.FILES) {
            List<JsonNode> examples = Examples.load(file);
            assertThat(examples).as(file).hasSize(COUNTS.get(file));
            Set<String> ids = new HashSet<>();
            for (JsonNode e : examples) {
                String id = e.path("id").asText();
                assertThat(ids.add(id)).as("duplicate id %s in %s", id, file).isTrue();
                assertThat(FUNCTIONS).as(id).contains(e.path("fn").asText());
                assertThat(e.has("expected")).as("%s has an expected value", id).isTrue();
            }
        }
    }
}
