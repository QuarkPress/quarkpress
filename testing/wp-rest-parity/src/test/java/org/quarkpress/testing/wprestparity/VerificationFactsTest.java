package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wporacle.OracleJson;

class VerificationFactsTest {

    static Exchange exchange(Map<String, List<String>> headers, String body) {
        RestCall call = new RestCall(
                "alice",
                "POST",
                "/wp-json/wp/v2/tags?x=1",
                Map.of("Origin", "https://x.test"),
                Optional.of("application/json"),
                Optional.of("{\"name\":\"a\"}".getBytes(StandardCharsets.UTF_8)));
        return new Exchange(call, 201, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    static JsonNode json(String text) throws Exception {
        return OracleJson.MAPPER.readTree(text);
    }

    @Test
    void anEntryKeepsTheRequestEveryHeaderLineUnderItsLowerCaseNameAndTheExactBody() throws Exception {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Link", List.of("<http://localhost/wp-json/>; rel=\"https://api.w.org/\""));
        headers.put("link", List.of("<http://localhost/wp-json/wp/v2/tags?page=2>; rel=\"next\""));
        headers.put("X-WP-Total", List.of("25"));
        String body = "{\"name\":\"Caf\\u00e9\\/Bar\",\"id\":7}";

        ObjectNode entry = VerificationFacts.toJson(exchange(headers, body));

        assertThat(entry.get("request"))
                .isEqualTo(json("{\"as\":\"alice\",\"method\":\"POST\",\"target\":\"/wp-json/wp/v2/tags?x=1\","
                        + "\"headers\":{\"Origin\":\"https://x.test\"},\"contentType\":\"application/json\","
                        + "\"body\":\"{\\\"name\\\":\\\"a\\\"}\"}"));
        assertThat(entry.get("status").isInt()).isTrue();
        assertThat(entry.get("status").intValue()).isEqualTo(201);
        assertThat(entry.get("headers").get("link")).hasSize(2);
        assertThat(entry.get("headers").get("x-wp-total")).isEqualTo(json("[\"25\"]"));
        assertThat(entry.get("headers").has("Link")).isFalse();
        assertThat(entry.get("body")).isEqualTo(json("{\"name\":\"Caf\u00e9/Bar\",\"id\":7}"));
        assertThat(entry.get("bodyText").textValue()).isEqualTo(body);
    }

    @Test
    void aBodyThatIsNotJsonOrHasTrailingTokensIsRecordedAsText() {
        for (String body : List.of("<!DOCTYPE html><p>error</p>", "{\"a\":1} <br />Notice", "[] []", "")) {
            ObjectNode entry = VerificationFacts.toJson(exchange(Map.of(), body));
            assertThat(entry.get("body").isTextual()).as("body of %s", body).isTrue();
            assertThat(entry.get("body").textValue()).isEqualTo(body);
            assertThat(entry.get("bodyText").textValue()).isEqualTo(body);
        }
    }

    @Test
    void savingMergesIntoTheExistingFactsReplacingOnlyTheItemsRecordedNow() throws Exception {
        JsonNode existing = json("{\"items\":{\"d01\":[{\"status\":301}],\"d06\":[{\"status\":500}]},"
                + "\"notes\":{\"d06\":{\"old\":1},\"d19\":{\"help body bytes\":519015}}}");
        ObjectNode items = (ObjectNode) json("{\"d06\":[{\"status\":401}],\"d07\":[{\"status\":400}]}");
        ObjectNode notes = (ObjectNode) json("{\"d07\":{\"term_id\":\"NUMBER\"}}");

        JsonNode merged = VerificationFacts.merge(existing, items, notes);

        assertThat(merged)
                .isEqualTo(json("{\"items\":{\"d01\":[{\"status\":301}],\"d06\":[{\"status\":401}],"
                        + "\"d07\":[{\"status\":400}]},"
                        + "\"notes\":{\"d19\":{\"help body bytes\":519015},\"d07\":{\"term_id\":\"NUMBER\"}}}"));
        assertThat(merged.get("items").fieldNames()).toIterable().containsExactly("d01", "d06", "d07");
    }

    @Test
    void savingWithoutAnExistingFileWritesOnlyTheItemsRecordedNow() throws Exception {
        ObjectNode items = (ObjectNode) json("{\"d06\":[{\"status\":401}]}");
        ObjectNode notes = OracleJson.MAPPER.createObjectNode();

        assertThat(VerificationFacts.merge(null, items, notes))
                .isEqualTo(json("{\"items\":{\"d06\":[{\"status\":401}]},\"notes\":{}}"));
    }
}
