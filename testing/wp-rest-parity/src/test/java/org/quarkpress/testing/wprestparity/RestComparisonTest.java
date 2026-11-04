package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;

/** The pretty-to-plain mapping of the front-end links posts carry, and of the alternate {@code Link} header. */
class RestComparisonTest {

    static JsonNode json(String text) {
        try {
            return OracleJson.MAPPER.readTree(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void prettyPostLinksMapToPlainForms() {
        assertThat(RestComparison.plainLink(
                        "{host}{site}/hello-world/",
                        json("{\"id\":\"p:a\",\"type\":\"post\",\"slug\":\"hello-world\"}")))
                .contains("{host}{site}/?p=p:a");
        assertThat(RestComparison.plainLink(
                        "{host}{site}/about/team/", json("{\"id\":\"p:t\",\"type\":\"page\",\"slug\":\"team\"}")))
                .contains("{host}{site}/?page_id=p:t");
        assertThat(RestComparison.plainLink(
                        "{host}{site}/about/", json("{\"id\":\"p:t\",\"type\":\"page\",\"slug\":\"about\"}")))
                .contains("{host}{site}/?page_id=p:t");
        // a published book: ?book=<slug>; a draft's link is plain on both sides already and is not mapped
        assertThat(RestComparison.plainLink(
                        "{host}{site}/book/moby-dick/",
                        json("{\"id\":\"p:b\",\"type\":\"book\",\"status\":\"publish\",\"slug\":\"moby-dick\"}")))
                .contains("{host}{site}/?book=moby-dick");
        assertThat(RestComparison.plainLink(
                        "{host}{site}/?post_type=book&p=p:b",
                        json("{\"id\":\"p:b\",\"type\":\"book\",\"status\":\"draft\",\"slug\":\"\"}")))
                .isEmpty();
        // the dated structure a network's subdirectory site has by default
        assertThat(RestComparison.plainLink(
                        "{host}{site}/2026/11/03/hello-world/",
                        json("{\"id\":\"p:a\",\"type\":\"post\",\"slug\":\"hello-world\"}")))
                .contains("{host}{site}/?p=p:a");
        // permalink templates: %postname% → ?p=<id>, %pagename% → ?page_id=<id>, a child page's too
        assertThat(RestComparison.plainLink(
                        "{host}{site}/%postname%/", json("{\"id\":\"p:a\",\"type\":\"post\",\"slug\":\"x\"}")))
                .contains("{host}{site}/?p=p:a");
        assertThat(RestComparison.plainLink(
                        "{host}{site}/%year%/%monthnum%/%day%/%postname%/",
                        json("{\"id\":\"p:a\",\"type\":\"post\",\"slug\":\"x\"}")))
                .contains("{host}{site}/?p=p:a");
        assertThat(RestComparison.plainLink(
                        "{host}{site}/%pagename%/", json("{\"id\":\"p:t\",\"type\":\"page\",\"slug\":\"x\"}")))
                .contains("{host}{site}/?page_id=p:t");
        assertThat(RestComparison.plainLink(
                        "{host}{site}/about/%pagename%/", json("{\"id\":\"p:t\",\"type\":\"page\",\"slug\":\"x\"}")))
                .contains("{host}{site}/?page_id=p:t");
        // a wrong slug is not mapped (it differs); an object without an id is not mapped
        assertThat(RestComparison.plainLink(
                        "{host}{site}/other/", json("{\"id\":\"p:a\",\"type\":\"post\",\"slug\":\"hello-world\"}")))
                .isEmpty();
        assertThat(RestComparison.plainLink(
                        "{host}{site}/hello-world/", json("{\"type\":\"post\",\"slug\":\"hello-world\"}")))
                .isEmpty();
        // the M2c forms still map
        assertThat(RestComparison.plainLink("{host}{site}/category/a/b/", json("{\"id\":\"t:b\",\"slug\":\"b\"}")))
                .contains("{host}{site}/?cat=t:b");
        assertThat(RestComparison.plainLink("{host}{site}/tag/t/", json("{\"slug\":\"t\"}")))
                .contains("{host}{site}/?tag=t");
        assertThat(RestComparison.plainLink(
                        "{host}{site}/author/carol/", json("{\"id\":\"u:carol\",\"slug\":\"carol\"}")))
                .contains("{host}{site}/?author=u:carol");
    }

    @Test
    void prettyPreviewLinksKeepTheirQuery() {
        assertThat(RestComparison.plainLink(
                        "{host}{site}/hello-world/?preview_id=p:a&preview_nonce={nonce}&preview=true",
                        json("{\"id\":\"p:a\",\"type\":\"post\",\"slug\":\"hello-world\"}")))
                .contains("{host}{site}/?p=p:a&preview_id=p:a&preview_nonce={nonce}&preview=true");
        assertThat(RestComparison.plainLink(
                        "{host}{site}/about/team/?preview_id=p:t&preview_nonce={nonce}&preview=true",
                        json("{\"id\":\"p:t\",\"type\":\"page\",\"slug\":\"team\"}")))
                .contains("{host}{site}/?page_id=p:t&preview_id=p:t&preview_nonce={nonce}&preview=true");
        // a draft's preview link is plain already: nothing to map
        assertThat(RestComparison.plainLink(
                        "{host}{site}/?p=p:a&preview=true", json("{\"id\":\"p:a\",\"type\":\"post\",\"slug\":\"\"}")))
                .isEmpty();
        // in a body, the mapped preview_link and permalink_template compare equal to QuarkPress's plain ones
        assertThat(aspects(
                        "/wp-json/wp/v2/posts/5/autosaves",
                        "{\"id\":7,\"parent\":5,\"preview_link\":"
                                + "\"http://localhost/s1/hello-world/?preview_id=5&preview_nonce=b8341e5552&preview=true\","
                                + "\"_links\":{\"parent\":[{\"href\":\"http://localhost/s1/wp-json/wp/v2/posts/5\"}]}}",
                        "{\"id\":9,\"parent\":6,\"preview_link\":"
                                + "\"http://localhost/s1/?p=6&preview_id=6&preview_nonce=AbCdEfGhIjKlMnOpQrStUvWxYz0&preview=true\","
                                + "\"_links\":{\"parent\":[{\"href\":\"http://localhost/s1/wp-json/wp/v2/posts/6\"}]}}",
                        Map.of("p:a", 5L, "r:x", 7L),
                        Map.of("p:a", 6L, "r:x", 9L)))
                .containsExactly("req1:body:link-form:/preview_link");
        assertThat(aspects(
                        "/wp-json/wp/v2/posts/5",
                        "{\"id\":5,\"type\":\"post\",\"slug\":\"hello-world\",\"link\":\"http://localhost/s1/hello-world/\","
                                + "\"permalink_template\":\"http://localhost/s1/%postname%/\"}",
                        "{\"id\":6,\"type\":\"post\",\"slug\":\"hello-world\",\"link\":\"http://localhost/s1/?p=6\","
                                + "\"permalink_template\":\"http://localhost/s1/?p=6\"}",
                        Map.of("p:a", 5L),
                        Map.of("p:a", 6L)))
                .containsExactly("req1:body:link-form:/link", "req1:body:link-form:/permalink_template");
        // a wrong id in QuarkPress's permalink_template still differs
        assertThat(aspects(
                        "/wp-json/wp/v2/posts/5",
                        "{\"id\":5,\"type\":\"post\",\"slug\":\"x\",\"permalink_template\":\"http://localhost/s1/%postname%/\"}",
                        "{\"id\":6,\"type\":\"post\",\"slug\":\"x\",\"permalink_template\":\"http://localhost/s1/?p=7\"}",
                        Map.of("p:a", 5L),
                        Map.of("p:a", 6L)))
                .containsExactly("req1:body:link-form:/permalink_template", "req1:body:/permalink_template");
    }

    @Test
    void searchItemUrlsMapByTheirKind() {
        assertThat(RestComparison.plainLink(
                        "{host}{site}/about/",
                        json("{\"id\":\"p:t\",\"type\":\"post\",\"subtype\":\"page\",\"url\":\"\"}")))
                .contains("{host}{site}/?page_id=p:t");
        assertThat(RestComparison.plainLink(
                        "{host}{site}/hello/",
                        json("{\"id\":\"p:a\",\"type\":\"post\",\"subtype\":\"post\",\"url\":\"\"}")))
                .contains("{host}{site}/?p=p:a");
        assertThat(RestComparison.plainLink(
                        "{host}{site}/book/moby-dick/",
                        json("{\"id\":\"p:b\",\"type\":\"post\",\"subtype\":\"book\",\"url\":\"\"}")))
                .contains("{host}{site}/?book=moby-dick");
        assertThat(RestComparison.plainLink(
                        "{host}{site}/category/uncategorized/",
                        json("{\"id\":\"t:uncategorized\",\"type\":\"category\"}")))
                .contains("{host}{site}/?cat=t:uncategorized");
        assertThat(RestComparison.plainLink(
                        "{host}{site}/tag/news/", json("{\"id\":\"t:news\",\"type\":\"post_tag\"}")))
                .contains("{host}{site}/?tag=news");
        assertThat(RestComparison.plainLink(
                        "{host}{site}/type/aside/", json("{\"id\":\"aside\",\"type\":\"post-format\"}")))
                .contains("{host}{site}/?post_format=aside");
        // in a search answer: each item's url is mapped by its kind, the ids by the kind the item names
        assertThat(aspects(
                        "/wp-json/wp/v2/search?search=x",
                        "[{\"id\":4,\"title\":\"About\",\"url\":\"http://localhost/s1/about/\",\"type\":\"post\",\"subtype\":\"page\"},"
                                + "{\"id\":2,\"title\":\"Uncategorized\",\"url\":\"http://localhost/s1/category/uncategorized/\",\"type\":\"category\"},"
                                + "{\"id\":\"aside\",\"title\":\"Aside\",\"url\":\"http://localhost/s1/type/aside/\",\"type\":\"post-format\"}]",
                        "[{\"id\":9,\"title\":\"About\",\"url\":\"http://localhost/s1/?page_id=9\",\"type\":\"post\",\"subtype\":\"page\"},"
                                + "{\"id\":3,\"title\":\"Uncategorized\",\"url\":\"http://localhost/s1/?cat=3\",\"type\":\"category\"},"
                                + "{\"id\":\"aside\",\"title\":\"Aside\",\"url\":\"http://localhost/s1/?post_format=aside\",\"type\":\"post-format\"}]",
                        Map.of("p:t", 4L, "t:uncategorized", 2L),
                        Map.of("p:t", 9L, "t:uncategorized", 3L)))
                .containsExactly(
                        "req1:body:link-form:/0/url", "req1:body:link-form:/1/url", "req1:body:link-form:/2/url");
    }

    @Test
    void theAlternateLinkHeaderIsMapped() {
        RestCall item = RestCall.get("anonymous", "/wp-json/wp/v2/posts/5");
        RestCall head = new RestCall(
                "anonymous", "HEAD", "/wp-json/wp/v2/pages/7", Map.of(), Optional.empty(), Optional.empty());
        RestCall list = RestCall.get("anonymous", "/wp-json/wp/v2/posts");
        String body = "{\"id\":5,\"type\":\"post\",\"slug\":\"hello-world\"}";
        List<Exchange> wp = List.of(
                new Exchange(
                        item,
                        200,
                        Map.of(
                                "Link",
                                List.of("<http://localhost/s1/hello-world/>; rel=\"alternate\"; type=text/html")),
                        bytes(body)),
                new Exchange(
                        head,
                        200,
                        Map.of("Link", List.of("<http://localhost/s1/about/>; rel=\"alternate\"; type=text/html")),
                        bytes("")),
                new Exchange(
                        list,
                        200,
                        Map.of("Link", List.of("<http://localhost/s1/wp-json/>; rel=\"https://api.w.org/\"")),
                        bytes("[]")));
        List<Exchange> qp = List.of(
                new Exchange(
                        item,
                        200,
                        Map.of("Link", List.of("<http://localhost/s1/?p=9>; rel=\"alternate\"; type=text/html")),
                        bytes("{\"id\":9,\"type\":\"post\",\"slug\":\"hello-world\"}")),
                new Exchange(
                        head,
                        200,
                        Map.of("Link", List.of("<http://localhost/s1/?page_id=8>; rel=\"alternate\"; type=text/html")),
                        bytes("")),
                new Exchange(
                        list,
                        200,
                        Map.of("Link", List.of("<http://localhost/s1/wp-json/>; rel=\"https://api.w.org/\"")),
                        bytes("[]")));
        assertThat(mismatches(
                        List.of(item, head, list), wp, qp, Map.of("p:a", 5L, "p:t", 7L), Map.of("p:a", 9L, "p:t", 8L)))
                .isEmpty();
        // the mapped form is compared: a wrong id or site on QuarkPress's side still differs, and so does a QuarkPress
        // item answer that keeps the API-root line next to the alternate one (WordPress sends the alternate line only)
        List<Exchange> wrong = List.of(
                new Exchange(
                        item,
                        200,
                        Map.of("Link", List.of("<http://localhost/s1/?p=8>; rel=\"alternate\"; type=text/html")),
                        bytes("{\"id\":9,\"type\":\"post\",\"slug\":\"hello-world\"}")),
                new Exchange(
                        head,
                        200,
                        Map.of("Link", List.of("<http://localhost/s2/?page_id=8>; rel=\"alternate\"; type=text/html")),
                        bytes("")),
                new Exchange(
                        list,
                        200,
                        Map.of("Link", List.of("<http://localhost/s1/wp-json/>; rel=\"https://api.w.org/\"")),
                        bytes("[]")));
        assertThat(mismatches(
                        List.of(item, head, list),
                        wp,
                        wrong,
                        Map.of("p:a", 5L, "p:t", 7L),
                        Map.of("p:a", 9L, "p:t", 8L)))
                .containsExactly("req1:header:link", "req2:header:link");
        List<Exchange> both = List.of(new Exchange(
                item,
                200,
                Map.of(
                        "Link",
                        List.of(
                                "<http://localhost/s1/wp-json/>; rel=\"https://api.w.org/\"",
                                "<http://localhost/s1/?p=9>; rel=\"alternate\"; type=text/html")),
                bytes("{\"id\":9,\"type\":\"post\",\"slug\":\"hello-world\"}")));
        assertThat(mismatches(List.of(item), List.of(wp.get(0)), both, Map.of("p:a", 5L), Map.of("p:a", 9L)))
                .containsExactly("req1:header:link");
    }

    @Test
    void anUnmappableLinkComparesEqualOnlyToALocalPlainLink() {
        Map<String, Long> wpRefs = Map.of("p:a", 5L);
        Map<String, Long> qpRefs = Map.of("p:a", 9L, "p:b", 8L);
        // an object without a slug: WordPress's pretty form cannot be mapped
        String wp = "{\"id\":5,\"link\":\"http://localhost/s1/some/where/\"}";
        String target = "/wp-json/wp/v2/things/5";
        assertThat(aspects(target, wp, "{\"id\":9,\"link\":\"http://localhost/s1/?p=9\"}", wpRefs, qpRefs))
                .containsExactly("req1:body:link-form:/link");
        assertThat(aspects(target, wp, "{\"id\":9,\"link\":\"http://localhost/s1/\"}", wpRefs, qpRefs))
                .containsExactly("req1:body:link-form:/link");
        // another site, another id, an unknown id or a pretty form of QuarkPress's own still differ
        for (String wrong : List.of(
                "http://localhost/s2/?p=9",
                "http://example.com/s1/?p=9",
                "http://localhost/s1/?p=8",
                "http://localhost/s1/?p=7",
                "http://localhost/s1/pretty/")) {
            assertThat(aspects(target, wp, "{\"id\":9,\"link\":\"" + wrong + "\"}", wpRefs, qpRefs))
                    .as(wrong)
                    .containsExactlyInAnyOrder("req1:body:link-form:/link", "req1:body:/link");
        }
    }

    @Test
    void theQuarkPressOnlyNamespaceIsItsOwnAspect() {
        String wp = "{\"namespaces\":[\"oembed/1.0\",\"wp/v2\",\"wp-site-health/v1\"],\"routes\":{}}";
        assertThat(aspects(
                        "/wp-json/",
                        wp,
                        "{\"namespaces\":[\"wp/v2\",\"quarkpress/v1\"],\"routes\":{}}",
                        Map.of(),
                        Map.of()))
                .containsExactlyInAnyOrder(
                        "req1:body:wp-only:/namespaces/oembed~11.0",
                        "req1:body:wp-only:/namespaces/wp-site-health~1v1",
                        "req1:body:qp-only:/namespaces/quarkpress~1v1");
        // any other namespace QuarkPress lists still differs
        assertThat(aspects(
                        "/wp-json/",
                        wp,
                        "{\"namespaces\":[\"wp/v2\",\"quarkpress/v1\",\"other/v1\"],\"routes\":{}}",
                        Map.of(),
                        Map.of()))
                .contains("req1:body:/namespaces/1");
    }

    /** The mismatching aspects of comparing one request's two bodies. */
    static List<String> aspects(
            String target, String wordpress, String quarkpress, Map<String, Long> wpRefs, Map<String, Long> qpRefs) {
        RestCall call = RestCall.get("anonymous", target);
        return mismatches(
                List.of(call),
                List.of(new Exchange(call, 200, Map.of(), bytes(wordpress))),
                List.of(new Exchange(call, 200, Map.of(), bytes(quarkpress))),
                wpRefs,
                qpRefs);
    }

    static List<String> mismatches(
            List<RestCall> calls,
            List<Exchange> wordpress,
            List<Exchange> quarkpress,
            Map<String, Long> wpRefs,
            Map<String, Long> qpRefs) {
        ParityRun run = new ParityRun("t", ParitySkipList.empty(), Path.of("target", "comparison-test.json"));
        new RestComparison(wpRefs, qpRefs, "/s1/")
                .compare(new RestScenario("s1", "test", List.of(), calls), wordpress, quarkpress, run);
        List<String> out = new ArrayList<>();
        run.mismatches().forEach(m -> out.add(m.aspect()));
        return out;
    }
}
