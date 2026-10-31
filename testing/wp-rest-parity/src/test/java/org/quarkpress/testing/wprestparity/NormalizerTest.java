package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;

class NormalizerTest {

    final Normalizer normalizer = new Normalizer();

    static JsonNode json(String text) {
        try {
            return OracleJson.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void hostsSitesIdsAndStampsAreNormalised() {
        JsonNode body = json(
                "{\"id\":12,\"parent\":7,\"link\":\"http://localhost/s1/?cat=12\",\"_links\":{\"self\":[{\"href\":"
                        + "\"http://127.0.0.1:41234/s1/wp-json/wp/v2/categories/12\",\"targetHints\":{\"allow\":[\"GET\"]}}],"
                        + "\"up\":[{\"embeddable\":true,\"href\":\"http://localhost/s1/wp-json/wp/v2/categories/7\"}]}}");
        assertThat(normalizer
                        .body(body, Map.of(12L, "t:child", 7L, "t:parent"), "/s1/")
                        .toString())
                .isEqualTo(
                        "{\"id\":\"t:child\",\"parent\":\"t:parent\",\"link\":\"http://{host}{site}/?cat=t:child\",\"_links\":{\"self\":"
                                + "[{\"href\":\"http://{host}{site}/wp-json/wp/v2/categories/t:child\",\"targetHints\":{\"allow\":[\"GET\"]}}],"
                                + "\"up\":[{\"embeddable\":true,\"href\":\"http://{host}{site}/wp-json/wp/v2/categories/t:parent\"}]}}");
    }

    @Test
    void anUnknownIdStaysVisible() {
        JsonNode body = json(
                "{\"id\":99,\"author\":3,\"_links\":{\"self\":[{\"href\":\"http://localhost/s1/wp-json/wp/v2/users/99\"}]}}");
        assertThat(normalizer.body(body, Map.of(3L, "u:carol"), "/s1/").toString())
                .isEqualTo("{\"id\":\"#?99\",\"author\":\"u:carol\",\"_links\":{\"self\":[{\"href\":"
                        + "\"http://{host}{site}/wp-json/wp/v2/users/#?99\"}]}}");
        // in URLs too: an unknown id in an id parameter or after a base that names its kind; other numbers stay
        assertThat(normalizer.header(
                        "Link",
                        "<http://localhost/s1/wp-json/wp/v2/tags/12?include=3,4&page=2&per_page=5>",
                        Map.of(3L, "t:a"),
                        "/s1/"))
                .isEqualTo("<http://{host}{site}/wp-json/wp/v2/tags/#?12?include=t:a,#?4&page=2&per_page=5>");
    }

    @Test
    void onlyTheIndexsOwnAuthenticationIsReplaced() {
        assertThat(normalizer
                        .body(json("{\"authentication\":[],\"x\":{\"authentication\":\"kept\"}}"), Map.of(), "/s1/")
                        .toString())
                .isEqualTo("{\"authentication\":\"{auth}\",\"x\":{\"authentication\":\"kept\"}}");
        assertThat(normalizer
                        .body(
                                json("{\"body\":{\"authentication\":{\"oidc\":{}}},\"status\":200,\"headers\":{}}"),
                                Map.of(),
                                "/s1/")
                        .toString())
                .isEqualTo("{\"body\":{\"authentication\":\"{auth}\"},\"status\":200,\"headers\":{}}");
        assertThat(normalizer
                        .body(json("[{\"authentication\":1}]"), Map.of(), "/s1/")
                        .toString())
                .isEqualTo("[{\"authentication\":1}]");
    }

    @Test
    void datesStampsAndTheAuthenticationAreReplacedAndOtherNumbersKept() {
        JsonNode body = json(
                "{\"registered_date\":\"2026-10-31T10:00:00+00:00\",\"meta\":{\"persisted_preferences\":"
                        + "{\"_modified\":\"2026-10-31T10:00:01.000Z\",\"core\":{\"a\":12}}},\"authentication\":{\"oidc\":{}},"
                        + "\"count\":12,\"url\":\"http://localhost/s1\",\"avatar\":\"https://secure.gravatar.com/avatar/12?s=24\"}");
        assertThat(normalizer.body(body, Map.of(12L, "t:x"), "/s1/").toString())
                .isEqualTo(
                        "{\"registered_date\":\"{date:dddd-dd-ddTdd:dd:dd+dd:dd}\",\"meta\":{\"persisted_preferences\":{\"_modified\":\"{stamp}\","
                                + "\"core\":{\"a\":12}}},\"authentication\":\"{auth}\",\"count\":12,\"url\":\"http://{host}{site}\","
                                + "\"avatar\":\"https://secure.gravatar.com/avatar/12?s=24\"}");
    }

    @Test
    void idsAreLookedUpAmongTheRefsOfTheirKind() {
        // on WordPress a subsite's terms and posts start again at 1, so user 3 and term 3 can share a number
        Normalizer.Refs refs = Normalizer.Refs.of(Map.of("u:carol", 3L, "t:news", 3L, "p:draft", 3L));
        JsonNode body = json(
                "{\"id\":3,\"author\":3,\"post\":3,\"taxonomy\":\"category\",\"parent\":3,"
                        + "\"_links\":{\"a\":[{\"href\":\"http://localhost/s1/wp-json/wp/v2/users/3?reassign=3&tags=3&include=3\"}]}}");
        assertThat(normalizer.body(body, refs, "/s1/", Normalizer.Kind.ANY).toString())
                .isEqualTo(
                        "{\"id\":\"t:news\",\"author\":\"u:carol\",\"post\":\"p:draft\",\"taxonomy\":\"category\","
                                + "\"parent\":\"t:news\",\"_links\":{\"a\":[{\"href\":"
                                + "\"http://{host}{site}/wp-json/wp/v2/users/u:carol?reassign=u:carol&tags=t:news&include=u:carol\"}]}}");
        assertThat(normalizer
                        .body(json("{\"id\":3}"), refs, "/s1/", Normalizer.Kind.USER)
                        .toString())
                .isEqualTo("{\"id\":\"u:carol\"}");
        assertThat(normalizer
                        .body(json("{\"id\":3,\"avatar_urls\":{}}"), refs, "/s1/", Normalizer.Kind.TERM)
                        .toString())
                .isEqualTo("{\"id\":\"u:carol\",\"avatar_urls\":{}}");
        assertThat(normalizer
                        .body(json("{\"id\":3}"), refs, "/s1/", Normalizer.Kind.ANY)
                        .toString())
                .isEqualTo("{\"id\":\"p:draft|t:news|u:carol\"}");
    }

    @Test
    void headerUrlsKeepTheirParametersAndOnlyIdParametersBecomeRefs() {
        String link = "<http://localhost/s1/wp-json/wp/v2/tags?include%5B0%5D=7&include%5B1%5D=8&per_page=7&page=2>;"
                + " rel=\"next\", <http://127.0.0.1:8081/s10/wp-json/>; rel=\"https://api.w.org/\"";
        assertThat(normalizer.header("Link", link, Map.of(7L, "t:a", 8L, "t:b", 2L, "t:c"), "/s1/"))
                .isEqualTo(
                        "<http://{host}{site}/wp-json/wp/v2/tags?include%5B0%5D=t:a&include%5B1%5D=t:b&per_page=7&page=2>;"
                                + " rel=\"next\", <http://{host}/s10/wp-json/>; rel=\"https://api.w.org/\"");
        assertThat(normalizer.header("X-WP-Total", "7", Map.of(7L, "t:a"), "/s1/"))
                .isEqualTo("7");
        // only whole items of an id parameter are ids: a slug with digits stays as it is
        assertThat(normalizer.header("Link", "<http://localhost/s1/?tag=d01-tag&cat=1,x1>", Map.of(1L, "t:a"), "/s1/"))
                .isEqualTo("<http://{host}{site}/?tag=d01-tag&cat=t:a,x1>");
        assertThat(normalizer.header(
                        "Link", "<http://localhost/s1/x?include=7%2C2>", Map.of(7L, "t:a", 2L, "t:c"), "/s1/"))
                .isEqualTo("<http://{host}{site}/x?include=t:a%2Ct:c>");
    }

    @Test
    void theComparisonReportsEachDifferingLeafAndKeepsEmptyListAndObjectApart() {
        ParityRun run =
                new ParityRun("t", ParitySkipList.empty(), java.nio.file.Path.of("target", "normalizer-test.json"));
        RestComparison comparison = new RestComparison(Map.of("t:a", 5L), Map.of("t:a", 9L), "/s1/");
        RestCall call = RestCall.get("anonymous", "/wp-json/wp/v2/tags");
        Exchange wp = new Exchange(
                call, 200, Map.of("Allow", List.of("GET")), bytes("{\"id\":5,\"meta\":[],\"name\":\"a\"}"));
        Exchange qp = new Exchange(
                call, 200, Map.of("Allow", List.of("GET")), bytes("{\"id\":9,\"meta\":{},\"name\":\"b\"}"));
        RestScenario scenario = new RestScenario("s1", "test", List.of(), List.of(call));
        comparison.compare(scenario, List.of(wp), List.of(qp), run);
        List<String> aspects = new ArrayList<>();
        run.mismatches().forEach(m -> aspects.add(m.aspect() + " " + m.wordpress() + " " + m.quarkpress()));
        assertThat(aspects).containsExactly("req1:body:/meta [] {}", "req1:body:/name \"a\" \"b\"");
        assertThat(run.comparisons()).isEqualTo(1 + RestComparison.HEADERS.size() + 2);
    }

    @Test
    void routesOnlyWordPressServesAreDroppedAndRecorded() {
        ParityRun run =
                new ParityRun("t", ParitySkipList.empty(), java.nio.file.Path.of("target", "normalizer-test.json"));
        RestComparison comparison = new RestComparison(Map.of(), Map.of(), "/s1/");
        RestCall call = RestCall.get("anonymous", "/wp-json/");
        Exchange wp = new Exchange(
                call,
                200,
                Map.of(),
                bytes(
                        "{\"namespaces\":[\"oembed/1.0\",\"wp/v2\"],\"routes\":{\"/\":{},\"/oembed/1.0\":{},\"/wp/v2\":{}}}"));
        Exchange qp = new Exchange(
                call, 200, Map.of(), bytes("{\"namespaces\":[\"wp/v2\"],\"routes\":{\"/\":{},\"/wp/v2\":{}}}"));
        comparison.compare(new RestScenario("s1", "test", List.of(), List.of(call)), List.of(wp), List.of(qp), run);
        List<String> aspects = new ArrayList<>();
        run.mismatches().forEach(m -> aspects.add(m.aspect()));
        assertThat(aspects)
                .containsExactly(
                        "req1:body:wp-only:/routes/~1oembed~11.0", "req1:body:wp-only:/namespaces/oembed~11.0");
    }

    @Test
    void routesOnlyWordPressServesAreAlsoDroppedInsideAnEnvelope() {
        ParityRun run =
                new ParityRun("t", ParitySkipList.empty(), java.nio.file.Path.of("target", "normalizer-test.json"));
        RestComparison comparison = new RestComparison(Map.of(), Map.of(), "/s1/");
        RestCall call = RestCall.get("anonymous", "/wp-json/?_envelope");
        Exchange wp = new Exchange(
                call,
                200,
                Map.of(),
                bytes("{\"body\":{\"namespaces\":[\"oembed/1.0\",\"wp/v2\"],"
                        + "\"routes\":{\"/oembed/1.0\":{},\"/wp/v2\":{}}},\"status\":200,\"headers\":{}}"));
        Exchange qp = new Exchange(
                call,
                200,
                Map.of(),
                bytes("{\"body\":{\"namespaces\":[\"wp/v2\"],"
                        + "\"routes\":{\"/wp/v2\":{}}},\"status\":200,\"headers\":{}}"));
        comparison.compare(new RestScenario("s1", "test", List.of(), List.of(call)), List.of(wp), List.of(qp), run);
        List<String> aspects = new ArrayList<>();
        run.mismatches().forEach(m -> aspects.add(m.aspect()));
        assertThat(aspects)
                .containsExactly(
                        "req1:body:wp-only:/body/routes/~1oembed~11.0",
                        "req1:body:wp-only:/body/namespaces/oembed~11.0");
    }

    /** The mismatching aspects of comparing one request's two bodies. */
    static List<String> aspects(String target, String wordpress, String quarkpress) {
        ParityRun run =
                new ParityRun("t", ParitySkipList.empty(), java.nio.file.Path.of("target", "normalizer-test.json"));
        RestCall call = RestCall.get("anonymous", target);
        new RestComparison(Map.of(), Map.of(), "/s1/")
                .compare(
                        new RestScenario("s1", "test", List.of(), List.of(call)),
                        List.of(new Exchange(call, 200, Map.of(), bytes(wordpress))),
                        List.of(new Exchange(call, 200, Map.of(), bytes(quarkpress))),
                        run);
        List<String> out = new ArrayList<>();
        run.mismatches().forEach(m -> out.add(m.aspect()));
        return out;
    }

    @Test
    void onlyTheNamedRoutesAndNamespacesQuarkPressDoesNotServeAreDropped() {
        // a route and a namespace QuarkPress serves but no longer lists still differ
        assertThat(aspects(
                        "/wp-json/",
                        "{\"namespaces\":[\"oembed/1.0\",\"wp/v2\"],\"routes\":{\"/wp/v2/posts\":{},"
                                + "\"/wp/v2/posts/(?P<id>[\\\\d]+)\":{},\"/wp/v2/tags\":{},\"/wp/v2/postsx\":{}}}",
                        "{\"namespaces\":[],\"routes\":{}}"))
                .containsExactly(
                        "req1:body:wp-only:/routes/~1wp~1v2~1posts",
                        "req1:body:wp-only:/routes/~1wp~1v2~1posts~1(?P<id>[\\d]+)",
                        "req1:body:wp-only:/namespaces/oembed~11.0",
                        "req1:body:/namespaces/0",
                        "req1:body:/routes/~1wp~1v2~1tags",
                        "req1:body:/routes/~1wp~1v2~1postsx");
    }

    @Test
    void theActiveThemeLinksAreDroppedFromTheIndexOnly() {
        assertThat(aspects(
                        "/wp-json/",
                        "{\"namespaces\":[],\"_links\":{\"help\":[],\"wp:active-theme\":[],\"curies\":[]}}",
                        "{\"namespaces\":[],\"_links\":{\"help\":[]}}"))
                .containsExactly("req1:body:wp-only:/_links/wp:active-theme", "req1:body:wp-only:/_links/curies");
        // an index request whose _fields leaves out the namespaces is still the index
        assertThat(aspects(
                        "/s1/wp-json/?_fields=_links",
                        "{\"_links\":{\"help\":[],\"wp:active-theme\":[]}}",
                        "{\"_links\":{\"help\":[]}}"))
                .containsExactly("req1:body:wp-only:/_links/wp:active-theme");
        assertThat(RestComparison.isIndexTarget("/s1/?rest_route=/&_fields=id")).isTrue();
        assertThat(RestComparison.isIndexTarget("/s1/wp-json/wp/v2/tags")).isFalse();
        assertThat(aspects(
                        "/wp-json/wp/v2/tags/1",
                        "{\"id\":1,\"_links\":{\"self\":[],\"curies\":[]}}",
                        "{\"id\":1,\"_links\":{\"self\":[]}}"))
                .containsExactly("req1:body:/_links/curies");
    }

    @Test
    void differingLinksAreRecordedOnceAndAMissingLinkStillDiffers() {
        assertThat(aspects(
                        "/wp-json/wp/v2/tags",
                        "[{\"link\":\"http://localhost/s1/tag/a/\",\"name\":\"a\"},{\"link\":\"x\"},{\"link\":\"y\"}]",
                        "[{\"link\":\"http://localhost/s1/?tag=a\",\"name\":\"a\"},{\"link\":\"x\"},{}]"))
                .containsExactly("req1:body:link-form:/0/link", "req1:body:/2/link");
    }

    @Test
    void wordPresssPrettyLinksAreComparedInTheirPlainForm() {
        // the plain form of each pretty link equals QuarkPress's: only the link-form aspect is recorded
        assertThat(aspects(
                        "/wp-json/wp/v2/categories",
                        "[{\"id\":7,\"slug\":\"b\",\"link\":\"http://localhost/s1/category/a/b/\"},"
                                + "{\"id\":3,\"slug\":\"carol\",\"link\":\"http://localhost/s1/author/carol/\"},"
                                + "{\"slug\":\"t\",\"link\":\"http://localhost/s1/tag/t/\"}]",
                        "[{\"id\":7,\"slug\":\"b\",\"link\":\"http://localhost/s1/?cat=7\"},"
                                + "{\"id\":3,\"slug\":\"carol\",\"link\":\"http://localhost/s1/?author=3\"},"
                                + "{\"slug\":\"t\",\"link\":\"http://localhost/s1/?tag=t\"}]"))
                .containsExactly(
                        "req1:body:link-form:/0/link", "req1:body:link-form:/1/link", "req1:body:link-form:/2/link");
        // a wrong id, slug, host or site in QuarkPress's link differs
        assertThat(aspects(
                        "/wp-json/wp/v2/categories",
                        "[{\"id\":7,\"slug\":\"b\",\"link\":\"http://localhost/s1/category/b/\"},"
                                + "{\"slug\":\"t\",\"link\":\"http://localhost/s1/tag/t/\"},"
                                + "{\"id\":3,\"slug\":\"c\",\"link\":\"http://localhost/s1/author/c/\"}]",
                        "[{\"id\":7,\"slug\":\"b\",\"link\":\"http://localhost/s1/?cat=8\"},"
                                + "{\"slug\":\"t\",\"link\":\"http://localhost/s1/?tag=u\"},"
                                + "{\"id\":3,\"slug\":\"c\",\"link\":\"http://localhost/s2/?author=3\"}]"))
                .containsExactly(
                        "req1:body:link-form:/0/link",
                        "req1:body:link-form:/1/link",
                        "req1:body:link-form:/2/link",
                        "req1:body:/0/link",
                        "req1:body:/1/link",
                        "req1:body:/2/link");
        // a pretty link whose slug is not the object's own is not mapped, and differs
        assertThat(aspects(
                        "/wp-json/wp/v2/categories",
                        "[{\"id\":7,\"slug\":\"b\",\"link\":\"http://localhost/s1/category/x/\"}]",
                        "[{\"id\":7,\"slug\":\"b\",\"link\":\"http://localhost/s1/?cat=7\"}]"))
                .containsExactly("req1:body:link-form:/0/link", "req1:body:/0/link");
        assertThat(RestComparison.plainLink(
                        "http://{host}{site}/category/a/b/", json("{\"id\":\"t:b\",\"slug\":\"b\"}")))
                .contains("http://{host}{site}/?cat=t:b");
        assertThat(RestComparison.plainLink("http://{host}{site}/category/b/", json("{\"slug\":\"b\"}")))
                .isEmpty();
    }

    @Test
    void rawBodiesKeepTheirBytesExceptTheNormalisedValues() {
        String wordpress = "{\"id\":12,\"name\":\"Caf\\u00e9\",\"link\":\"http:\\/\\/localhost\\/s1\\/?tag=x\","
                + "\"auth\":{\"a\":[1, 2]}}";
        JsonNode parsed = json(wordpress);
        JsonNode normalised = normalizer.body(parsed, Map.of(12L, "t:x"), "/s1/");
        assertThat(RawJson.normalised(bytes(wordpress), parsed, normalised))
                .isEqualTo("{\"id\":\"t:x\",\"name\":\"Caf\\u00e9\",\"link\":\"http://{host}{site}/?tag=x\","
                        + "\"auth\":{\"a\":[1, 2]}}");
        // a replaced container, and a value the normalised tree lacks
        JsonNode changed = normalised.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("auth", "{auth}");
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed).remove("name");
        assertThat(RawJson.normalised(bytes(wordpress), parsed, changed))
                .isEqualTo("{\"id\":\"t:x\",\"name\":<absent>,\"link\":\"http://{host}{site}/?tag=x\","
                        + "\"auth\":\"{auth}\"}");
        // the raw comparison of a byte-level scenario sees escapes and the order of keys
        ParityRun run =
                new ParityRun("t", ParitySkipList.empty(), java.nio.file.Path.of("target", "normalizer-test.json"));
        RestCall call = RestCall.get("anonymous", "/wp-json/wp/v2/tags/1");
        RestComparison comparison = new RestComparison(Map.of(), Map.of(), "/d14/");
        RestScenario d14 = new RestScenario("d14", "test", List.of(), List.of(call, call, call));
        comparison.compare(
                d14,
                List.of(
                        new Exchange(call, 200, Map.of(), bytes("{\"a\":\"\\u00e9\",\"b\":1}")),
                        new Exchange(call, 200, Map.of(), bytes("{\"a\":\"x\",\"b\":1}")),
                        new Exchange(call, 200, Map.of(), bytes("{\"a\":\"x\",\"b\":1}"))),
                List.of(
                        new Exchange(call, 200, Map.of(), bytes("{\"a\":\"\u00e9\",\"b\":1}")),
                        new Exchange(call, 200, Map.of(), bytes("{\"b\":1,\"a\":\"x\"}")),
                        new Exchange(call, 200, Map.of(), bytes("{\"a\":\"x\",\"b\":1}"))),
                run);
        List<String> found = new ArrayList<>();
        run.mismatches().forEach(m -> found.add(m.aspect()));
        assertThat(found).containsExactly("req1:body:raw", "req2:body:raw");
        // other scenarios compare parsed bodies only
        assertThat(aspects("/wp-json/wp/v2/tags/1", "{\"a\":\"x\",\"b\":1}", "{\"b\":1,\"a\":\"x\"}"))
                .isEmpty();
    }

    @Test
    void securityHeadersAreComparedAndCorsHeadersWhenAnOriginWasSent() {
        ParityRun run =
                new ParityRun("t", ParitySkipList.empty(), java.nio.file.Path.of("target", "normalizer-test.json"));
        RestCall plain = RestCall.get("anonymous", "/wp-json/wp/v2/tags");
        RestCall origin = new RestCall(
                "anonymous",
                "GET",
                "/wp-json/wp/v2/tags",
                Map.of("Origin", "https://x.test"),
                Optional.empty(),
                Optional.empty());
        Map<String, List<String>> wpHeaders = Map.of(
                "Cache-Control", List.of("no-cache"),
                "Expires", List.of("Wed, 11 Jan 1984 05:00:00 GMT"),
                "X-Content-Type-Options", List.of("nosniff"),
                "Vary", List.of("Origin"),
                "Access-Control-Allow-Origin", List.of("https://x.test"),
                "Access-Control-Allow-Credentials", List.of("true"),
                "Access-Control-Allow-Methods", List.of("OPTIONS, GET"));
        new RestComparison(Map.of(), Map.of(), "/s1/")
                .compare(
                        new RestScenario("s1", "test", List.of(), List.of(plain, origin)),
                        List.of(
                                new Exchange(plain, 200, wpHeaders, bytes("[]")),
                                new Exchange(origin, 200, wpHeaders, bytes("[]"))),
                        List.of(
                                new Exchange(plain, 200, Map.of(), bytes("[]")),
                                new Exchange(origin, 200, Map.of(), bytes("[]"))),
                        run);
        List<String> found = new ArrayList<>();
        run.mismatches().forEach(m -> found.add(m.aspect()));
        assertThat(found)
                .containsExactly(
                        "req1:header:x-content-type-options",
                        "req1:header:cache-control",
                        "req1:header:expires",
                        "req1:header:vary",
                        "req2:header:x-content-type-options",
                        "req2:header:cache-control",
                        "req2:header:expires",
                        "req2:header:vary",
                        "req2:header:access-control-allow-origin",
                        "req2:header:access-control-allow-credentials",
                        "req2:header:access-control-allow-methods");
    }

    @Test
    void aNonJsonBodyIsComparedAsNormalisedText() {
        assertThat(RestComparison.json(bytes("/**/cb({})"))).isEqualTo(Optional.empty());
        assertThat(RestComparison.json(bytes("{bad"))).isEqualTo(Optional.empty());
        assertThat(normalizer.text("<a href=\"http://localhost/s1/wp-json/\">", Map.of(), "/s1/"))
                .isEqualTo("<a href=\"http://{host}{site}/wp-json/\">");
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
