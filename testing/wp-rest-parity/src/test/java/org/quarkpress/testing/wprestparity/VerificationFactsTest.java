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
    void theHeaderLinesSentAreRecordedInOrderOnlyWhenThereAreSome() throws Exception {
        Map<String, List<String>> lines = new LinkedHashMap<>();
        lines.put("Content-Disposition", List.of("attachment; filename=\"b.png\"", "attachment; filename=\"a.png\""));
        lines.put("Content-MD5", List.of(""));
        RestCall call =
                RestCall.raw("alice", "/wp-json/wp/v2/media", Optional.of("image/png"), lines, new byte[] {'x'});

        ObjectNode entry = VerificationFacts.toJson(new Exchange(call, 201, Map.of(), new byte[0]));

        assertThat(entry.get("request").get("headerLines"))
                .isEqualTo(json("{\"Content-Disposition\":[\"attachment; filename=\\\"b.png\\\"\",\"attachment;"
                        + " filename=\\\"a.png\\\"\"],\"Content-MD5\":[\"\"]}"));
        assertThat(VerificationFacts.toJson(exchange(Map.of(), "{}"))
                        .get("request")
                        .has("headerLines"))
                .isFalse();
    }

    @Test
    void anHtmlPageIsSummarisedWithItsTitleBodyClassFragmentsAndDigest() {
        String html = "<html><head><title>Edit Post</title></head><body class=\"wp-admin post-php\">"
                + "<time datetime=\"2026-11-03T05:06:08+00:00\">November 3, 2026</time>"
                + "<script>wp.editPost.initializeEditor( 'editor', \"post\", 131, {\"postLock\":{\"isLocked\":true}});"
                + "</script></body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ObjectNode summary =
                VerificationFacts.htmlSummary(html, bytes, VerificationFacts.DIRECTORY.resolve("pages/f14-1.html"));
        assertThat(summary.get("page").asText()).isEqualTo("pages/f14-1.html");
        assertThat(summary.get("length").asInt()).isEqualTo(bytes.length);
        assertThat(summary.get("sha256").asText()).hasSize(64);
        assertThat(summary.get("title").asText()).isEqualTo("Edit Post");
        assertThat(summary.get("bodyClass").asText()).isEqualTo("wp-admin post-php");
        assertThat(summary.get("head").asText()).isEqualTo(html);
        assertThat(summary.at("/fragments/postLock").asText()).startsWith("\"postLock\":{\"isLocked\":true}");
        assertThat(summary.at("/fragments/initializeEditor").asText()).startsWith("initializeEditor( 'editor'");
        assertThat(summary.at("/fragments/time").get(0).asText())
                .isEqualTo("<time datetime=\"2026-11-03T05:06:08+00:00\">November 3, 2026</time>");
        assertThat(summary.at("/fragments/wpDie").isMissingNode()).isTrue();

        String die = "<html><head><title>WordPress &rsaquo; Error</title><style>.wp-die-message { margin: 0 }</style>"
                + "</head><body id=\"error-page\"><div class=\"wp-die-message\"><p>Sorry, you are not allowed to preview"
                + " drafts.</p></div></body></html>";
        ObjectNode dieSummary = VerificationFacts.htmlSummary(
                die, die.getBytes(StandardCharsets.UTF_8), VerificationFacts.DIRECTORY.resolve("pages/f16-6.html"));
        assertThat(dieSummary.at("/fragments/wpDie").asText())
                .startsWith("class=\"wp-die-message\"><p>Sorry, you are not allowed to preview drafts.</p>");
        assertThat(dieSummary.get("bodyClass").asText()).isEmpty();
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
