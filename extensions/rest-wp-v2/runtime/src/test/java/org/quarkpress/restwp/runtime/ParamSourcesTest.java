package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParamSourcesTest {

    @Test
    void precedenceIsJsonBodyQueryUrlDefaults() {
        ParamSources sources = new ParamSources(
                json("{\"a\":\"json\"}"),
                form("a=body&b=body"),
                query("a=q&b=q&c=q&id=7"),
                Map.of("id", "5", "d", "url"),
                json("{\"e\":\"default\",\"d\":\"default\"}"));
        assertThat(texts(sources, "a", "b", "c", "id", "d", "e"))
                .containsExactly("json", "body", "q", "7", "url", "default");
        assertThat(new ParamSources(json("{\"a\":null}"), empty(), query("a=q"), Map.of(), empty()).get("a"))
                .contains(TextNode.valueOf("q"));
    }

    @Test
    void absentEverywhereIsAbsent() {
        ParamSources sources = new ParamSources(json("{\"a\":null}"), empty(), empty(), Map.of(), empty());
        assertThat(sources.get("a")).isEmpty();
        assertThat(sources.has("a")).isFalse();
        assertThat(sources.order()).hasSize(5);
        assertThat(sources.order().get(3)).isEqualTo(json("{}"));
    }

    @Test
    void theMethodOverrideReadsTheQueryFirstThenTheHeader() {
        assertThat(RestServer.method(incoming("GET", "_method=delete", Map.of())))
                .isEqualTo("DELETE");
        assertThat(RestServer.method(incoming("POST", "", Map.of("x-http-method-override", "patch"))))
                .isEqualTo("PATCH");
        assertThat(RestServer.method(incoming("GET", "_method=post", Map.of("X-HTTP-Method-Override", "DELETE"))))
                .isEqualTo("POST");
        assertThat(RestServer.method(incoming("head", "", Map.of()))).isEqualTo("HEAD");
        // only ASCII letters change case, as PHP's strtoupper
        assertThat(RestServer.method(incoming("GET", "_method=g%C3%A9t", Map.of())))
                .isEqualTo("G" + new String(Character.toChars(0xE9)) + "T");
        // an array _method is no override
        assertThat(RestServer.method(incoming("GET", "_method[]=post", Map.of())))
                .isEqualTo("GET");
    }

    @Test
    void bodiesAreReadAsWordPressReadsThem() {
        IncomingRequest jsonGet = withBody("GET", "application/vnd.example+json; charset=utf-8", "{\"id\":\"9\"}");
        assertThat(RestServer.jsonParams(jsonGet)).isEqualTo(json("{\"id\":\"9\"}"));
        assertThat(RestServer.jsonParams(withBody("POST", "text/plain", "{\"id\":\"9\"}")))
                .isEqualTo(empty());
        assertThat(RestServer.jsonParams(withBody("POST", "application/json", "[\"a\",\"b\"]")))
                .isEqualTo(json("{\"0\":\"a\",\"1\":\"b\"}"));
        assertThat(RestServer.jsonParams(withBody("POST", "application/json", "null")))
                .isEqualTo(empty());
        assertThat(RestServer.jsonParams(withBody("POST", "application/json", "0")))
                .isEqualTo(empty());
        assertThat(RestServer.jsonParams(withBody("POST", "application/json", "{\"a\":")))
                .isEqualTo(empty());
        assertThat(RestServer.jsonFailure(withBody("POST", "application/json", "{\"a\":")))
                .contains(new RestJsonFailure(4, "Syntax error"));
        assertThat(RestServer.jsonFailure(withBody("GET", "Application/JSON", "[1}")))
                .contains(new RestJsonFailure(2, "State mismatch (invalid or malformed JSON)"));
        assertThat(RestServer.jsonFailure(withBody("GET", "text/plain", "[1}"))).isEmpty();
        // form bodies: for POST what PHP read; for other body methods the body when the type is absent or exactly
        // the form one; never for GET
        IncomingRequest put = withBody("PUT", null, "id=8&x[]=1");
        assertThat(RestServer.bodyParams(put, "PUT")).isEqualTo(json("{\"id\":\"8\",\"x\":{\"0\":\"1\"}}"));
        assertThat(RestServer.bodyParams(put, "GET")).isEqualTo(empty());
        assertThat(RestServer.bodyParams(
                        withBody("PATCH", "application/x-www-form-urlencoded; charset=UTF-8", "id=8"), "PATCH"))
                .isEqualTo(empty());
        IncomingRequest post = new IncomingRequest(
                "POST", "/x", empty(), Map.of(), "id=8&y=1".getBytes(StandardCharsets.UTF_8), form("id=8&y=1"));
        assertThat(RestServer.bodyParams(post, "POST")).isEqualTo(json("{\"id\":\"8\",\"y\":\"1\"}"));
        assertThat(RestServer.bodyParams(post, "DELETE")).isEqualTo(json("{\"id\":\"8\",\"y\":\"1\"}"));
        assertThat(RestServer.bodyParams(post, "OPTIONS")).isEqualTo(empty());
    }

    @Test
    void jsonContentTypesAreWordPresss() {
        for (String type : new String[] {
            "application/json",
            "APPLICATION/JSON",
            "application/json; charset=utf-8",
            "application/vnd.api+json",
            "application/json+oembed",
            " application/ld+json ;x=1"
        }) {
            assertThat(IncomingRequest.isJson(type)).as(type).isTrue();
        }
        for (String type : new String[] {"text/json", "application/jsonx", "application/+json", "json", "", null}) {
            assertThat(IncomingRequest.isJson(type)).as(type).isFalse();
        }
    }

    private static IncomingRequest incoming(String method, String query, Map<String, String> headers) {
        Map<String, List<String>> all = new java.util.HashMap<>();
        headers.forEach((name, value) -> all.put(name, List.of(value)));
        return new IncomingRequest(method, "/x", query(query), all, new byte[0], empty());
    }

    private static IncomingRequest withBody(String method, String contentType, String body) {
        Map<String, List<String>> headers =
                contentType == null ? Map.of() : Map.of("Content-Type", List.of(contentType));
        return new IncomingRequest(method, "/x", empty(), headers, body.getBytes(StandardCharsets.UTF_8), empty());
    }

    private static List<String> texts(ParamSources sources, String... names) {
        List<String> texts = new ArrayList<>();
        for (String name : names) {
            texts.add(sources.get(name).map(JsonNode::textValue).orElse(null));
        }
        return texts;
    }

    private static ObjectNode json(String text) {
        try {
            return (ObjectNode) RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectNode form(String text) {
        return PhpQuery.parse(text);
    }

    private static ObjectNode query(String text) {
        return PhpQuery.parse(text);
    }

    private static ObjectNode empty() {
        return RestJson.NODES.objectNode();
    }
}
