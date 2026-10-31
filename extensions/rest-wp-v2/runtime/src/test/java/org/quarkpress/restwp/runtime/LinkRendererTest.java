package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestUrls;

/**
 * WordPress's rendering of links: grouped by relation, attributes before {@code href}, the {@code self} link's target
 * hints for the caller, {@code https://api.w.org/} relations compacted to {@code wp:} with the curie listed last.
 */
class LinkRendererTest {

    /** The site's URLs below {@code http://localhost/}. */
    static final RestUrls URLS = urls("http://localhost/");

    /** The URLs of a site whose home is {@code home}, with WordPress's {@code wp-json} base. */
    static RestUrls urls(String home) {
        return new RestUrls() {
            @Override
            public String root() {
                return home + "wp-json/";
            }

            @Override
            public String route(String path) {
                return root() + (path.startsWith("/") ? path.substring(1) : path);
            }

            @Override
            public String route(String path, Map<String, String> query) {
                StringBuilder url = new StringBuilder(route(path));
                char separator = '?';
                for (Map.Entry<String, String> entry : query.entrySet()) {
                    url.append(separator)
                            .append(RequestRestUrls.urlencode(entry.getKey()))
                            .append('=')
                            .append(RequestRestUrls.urlencode(entry.getValue()));
                    separator = '&';
                }
                return url.toString();
            }

            @Override
            public String home() {
                return home;
            }
        };
    }

    @Test
    void shapeOrderTargetHintsAndCuries() {
        Links links = Links.none()
                .add("self", URLS.route("/qp-test/v1/things/3"))
                .add("collection", URLS.route("/qp-test/v1/things"))
                .add("up", URLS.route("/qp-test/v1/things/1"), Map.of("embeddable", BooleanNode.TRUE))
                .add("https://api.w.org/post_type", URLS.route("/wp/v2/posts", Map.of("categories", "3")));
        assertThat(render(links).toString())
                .isEqualTo("{\"self\":[{\"href\":\"http://localhost/wp-json/qp-test/v1/things/3\","
                        + "\"targetHints\":{\"allow\":[\"GET\"]}}],\"collection\":[{\"href\":"
                        + "\"http://localhost/wp-json/qp-test/v1/things\"}],"
                        + "\"up\":[{\"embeddable\":true,\"href\":\"http://localhost/wp-json/qp-test/v1/things/1\"}],"
                        + "\"wp:post_type\":[{\"href\":\"http://localhost/wp-json/wp/v2/posts?categories=3\"}],"
                        + "\"curies\":[{\"name\":\"wp\",\"href\":\"https://api.w.org/{rel}\",\"templated\":true}]}");
    }

    @Test
    void noCuriesWithoutApiRelationsAndNoHintsForUnknownRoutes() {
        assertThat(render(Links.none().add("self", URLS.route("/qp-test/v1/none")))
                        .toString())
                .isEqualTo("{\"self\":[{\"href\":\"http://localhost/wp-json/qp-test/v1/none\"}]}");
    }

    @Test
    void compactedRelationsMoveAfterThePlainOnesAndRelationsGroupInFirstSeenOrder() {
        Links links = Links.none()
                .add("https://api.w.org/term", "http://localhost/wp-json/wp/v2/tags?post=1")
                .add("about", "http://localhost/a")
                .add("https://api.w.org/term", "http://localhost/wp-json/wp/v2/categories?post=1")
                // nothing after the prefix: not compacted
                .add("https://api.w.org/", "http://localhost/b")
                .add("author", "http://localhost/c");
        assertThat(fieldNames(render(links)))
                .containsExactly("about", "https://api.w.org/", "author", "wp:term", "curies");
        assertThat(render(links).at("/wp:term/1/href").asText())
                .isEqualTo("http://localhost/wp-json/wp/v2/categories?post=1");
    }

    @Test
    void hintsFollowTheCallerTheQueryAndAnExplicitAllow() {
        // the hints are the GET's Allow: an invalid query (per_page 0 on the collection) gives none
        assertThat(render(Links.none().add("self", URLS.route("/qp-test/v1/things", Map.of("per_page", "0"))))
                        .at("/self/0")
                        .has("targetHints"))
                .isFalse();
        assertThat(render(Links.none().add("self", URLS.route("/qp-test/v1/things", Map.of("per_page", "5"))))
                        .at("/self/0/targetHints")
                        .toString())
                .isEqualTo("{\"allow\":[\"GET\"]}");
        // another host, or no route at all below the root: none
        assertThat(render(Links.none().add("self", "http://elsewhere/wp-json/qp-test/v1/things/3"))
                        .at("/self/0")
                        .has("targetHints"))
                .isFalse();
        // a link that names its own allowed methods keeps them
        Map<String, JsonNode> given = new LinkedHashMap<>();
        ObjectNode hints = RestJson.NODES.objectNode();
        hints.putArray("allow").add("PUT");
        given.put("targetHints", hints);
        assertThat(render(Links.none().add("self", URLS.route("/qp-test/v1/things/3"), given))
                        .toString())
                .isEqualTo("{\"self\":[{\"targetHints\":{\"allow\":[\"PUT\"]},"
                        + "\"href\":\"http://localhost/wp-json/qp-test/v1/things/3\"}]}");
        // only self links get hints
        assertThat(render(Links.none().add("item", URLS.route("/qp-test/v1/things/3")))
                        .toString())
                .isEqualTo("{\"item\":[{\"href\":\"http://localhost/wp-json/qp-test/v1/things/3\"}]}");
    }

    @Test
    void anHrefAttributeNeverMovesTheHrefButANullOneKeepsItsPlace() {
        Map<String, JsonNode> given = new LinkedHashMap<>();
        given.put("href", RestJson.NODES.textNode("http://localhost/other"));
        given.put("embeddable", BooleanNode.TRUE);
        assertThat(render(Links.none().add("up", "http://localhost/u", given)).toString())
                .isEqualTo("{\"up\":[{\"embeddable\":true,\"href\":\"http://localhost/u\"}]}");
        Map<String, JsonNode> nullHref = new LinkedHashMap<>();
        nullHref.put("href", RestJson.NODES.nullNode());
        nullHref.put("embeddable", BooleanNode.TRUE);
        assertThat(render(Links.none().add("up", "http://localhost/u", nullHref))
                        .toString())
                .isEqualTo("{\"up\":[{\"href\":\"http://localhost/u\",\"embeddable\":true}]}");
    }

    @Test
    void anItemOfACollectionCarriesItsRenderedLinks() {
        ObjectNode body = RestJson.NODES.objectNode().put("id", 3);
        RestResponse item =
                RestResponse.ok(body).withLinks(Links.none().add("self", URLS.route("/qp-test/v1/things/3")));
        assertThat(CollectionItems.inline(item, caller()).toString())
                .isEqualTo("{\"id\":3,\"_links\":{\"self\":[{\"href\":\"http://localhost/wp-json/qp-test/v1/things/3\","
                        + "\"targetHints\":{\"allow\":[\"GET\"]}}]}}");
        // the item's own body is left as it was
        assertThat(body.toString()).isEqualTo("{\"id\":3}");
        assertThat(CollectionItems.inline(RestResponse.ok(body), caller()).toString())
                .isEqualTo("{\"id\":3}");
    }

    @Test
    void linksLeadOnlyIntoThisSite() {
        RouteTable table = table();
        LinkRenderer main = new LinkRenderer(table, URLS);
        // another site's path on the same host: neither its API root nor its home counts
        assertThat(main.target("http://localhost/news/wp-json/qp-test/v1/things/3"))
                .isEmpty();
        // a rest_route below this site's home is followed on this site, as WordPress follows it
        assertThat(main.target("http://localhost/news/?rest_route=/qp-test/v1/things/3"))
                .map(LinkRenderer.Target::route)
                .contains("/qp-test/v1/things/3");
        // dot segments and percent-encoded ones are taken literally (WordPress decodes neither): no route matches
        for (String href : List.of(
                "http://localhost/wp-json/../news/wp-json/qp-test/v1/things/3",
                "http://localhost/wp-json/%2e%2e/news/wp-json/qp-test/v1/things/3",
                "http://localhost/wp-json/qp-test/v1/things/%33",
                "http://localhost/wp-json/qp-test/v1/things/3/..")) {
            String route = main.target(href).orElseThrow().route();
            assertThat(route).as(href).isEqualTo(href.substring("http://localhost/wp-json".length()));
            assertThat(table.match(route, "GET")).as(href).isEmpty();
        }
        // two slashes after the root would name a host to PHP: nowhere
        assertThat(main.target("http://localhost/wp-json//elsewhere/qp-test/v1/things/3"))
                .isEmpty();
        LinkRenderer news = new LinkRenderer(table, urls("http://localhost/news/"));
        assertThat(news.target("http://localhost/wp-json/qp-test/v1/things/3")).isEmpty();
        assertThat(news.target("http://localhost/?rest_route=/qp-test/v1/things/3"))
                .isEmpty();
        assertThat(news.target("http://localhost/news-other/wp-json/qp-test/v1/things/3"))
                .isEmpty();
        assertThat(news.target("http://localhost/news/wp-json/qp-test/v1/things/3?a=b#c"))
                .map(target -> target.route() + " " + target.query())
                .contains("/qp-test/v1/things/3 {\"a\":\"b\"}");
    }

    private static ObjectNode render(Links links) {
        return LinkRenderer.render(links, caller());
    }

    /** An anonymous caller whose links resolve against a table with the things' routes. */
    private static StubRequest caller() {
        return new StubRequest(RestJson.NODES.objectNode(), URLS.route("/qp-test/v1/things"), Set.of())
                .withLinkRenderer(new LinkRenderer(table(), URLS));
    }

    private static RouteTable table() {
        RouteTable table = new RouteTable();
        table.registry()
                .route("qp-test/v1", "/things")
                .endpoint(Endpoint.of("GET")
                        .arg(Arg.of("per_page", "integer")
                                .minimum(1)
                                .maximum(100)
                                .build())
                        .handler(request -> RestResponse.ok(RestJson.NODES.arrayNode()))
                        .build())
                .register();
        table.registry()
                .route("qp-test/v1", "/things/(?P<id>[\\d]+)")
                .endpoint(Endpoint.of("GET")
                        .handler(request -> RestResponse.ok(RestJson.NODES.objectNode()))
                        .build())
                .endpoint(Endpoint.of("POST", "PUT", "PATCH")
                        .permission(PermissionResult::forbidden)
                        .handler(request -> RestResponse.ok(RestJson.NODES.objectNode()))
                        .build())
                .register();
        return table;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
