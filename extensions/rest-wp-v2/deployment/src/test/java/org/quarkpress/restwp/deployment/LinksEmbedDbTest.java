package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * WordPress's links and embedding: the {@code self} link's target hints for the caller, links inlined into collection
 * items before {@code _fields}, and {@code _embed} answered by dispatching each embeddable link internally as the same
 * caller (Part D 16, 17, 18).
 */
class LinksEmbedDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(LinksEmbedDbTest.class, TestRoutes.class);

    static final String NODES = "/wp-json/qp-test/v1/nodes";

    long administrator;
    long author;

    @BeforeEach
    void users() {
        AgroalDataSource data = Jdbc.dataSource();
        administrator = TestData.memberOfMain(data, "administrator").value();
        author = TestData.memberOfMain(data, "author").value();
        TestRoutes.NODE_CALLS.clear();
    }

    @Test
    void targetHintsArePerCaller() {
        assertThat(hints(null, "/wp-json/qp-test/v1/things/3")).containsExactly("GET");
        assertThat(hints("test-" + administrator, "/wp-json/qp-test/v1/things/3"))
                .containsExactly("GET", "POST", "PUT", "PATCH", "DELETE");
        assertThat(hints("test-" + author, "/wp-json/qp-test/v1/things/3"))
                .containsExactly("GET", "POST", "PUT", "PATCH");
        // Part D 17: the hints equal the Allow header of a GET of the same href by the same caller
        List<String> callers = new ArrayList<>(Arrays.asList((String) null));
        callers.add("test-" + administrator);
        callers.add("test-" + author);
        for (String caller : callers) {
            JsonNode self = json(Http.as(caller)
                            .get("/wp-json/qp-test/v1/things/3")
                            .asString())
                    .at("/_links/self/0");
            String href = self.get("href").asText();
            assertThat(href).startsWith("http://localhost/");
            String allow = Http.as(caller)
                    .get(href.substring("http://localhost".length()))
                    .header("Allow");
            assertThat(String.join(", ", texts(self.at("/targetHints/allow"))))
                    .as(String.valueOf(caller))
                    .isEqualTo(allow);
        }
    }

    @Test
    void anItemHasLinksOnlyWhenTheyAreSelected() {
        // the controller adds them only when _links (or _embedded) is selected; then they come after the fields
        assertThat(Http.as(null).get(NODES + "/3?_fields=id").asString()).isEqualTo("{\"id\":3}");
        JsonNode linked = json(Http.as(null).get(NODES + "/3?_fields=id,_links").asString());
        assertThat(fieldNames(linked)).containsExactly("id", "_links");
        assertThat(fieldNames(linked.get("_links"))).containsExactly("self", "up", "author", "related");
        assertThat(linked.at("/_links/up/0").toString())
                .isEqualTo("{\"embeddable\":true,\"href\":\"http://localhost/wp-json/qp-test/v1/nodes/1\"}");
        assertThat(linked.at("/_links/self/0").toString())
                .isEqualTo("{\"href\":\"http://localhost/wp-json/qp-test/v1/nodes/3\","
                        + "\"targetHints\":{\"allow\":[\"GET\"]}}");
        // without _embed, nothing is embedded and no other handler runs
        assertThat(linked.has("_embedded")).isFalse();
        assertThat(calls("1")).isZero();
    }

    @Test
    void embedsTheEmbeddableLinksInEmbedContextOnce() {
        JsonNode node = json(Http.as(null).get(NODES + "/3?_embed").asString());
        assertThat(fieldNames(node)).endsWith("_links", "_embedded");
        assertThat(node.at("/_embedded/up/0/context").asText()).isEqualTo("embed");
        assertThat(node.at("/_embedded/up/0/id").toString()).isEqualTo("1");
        assertThat(node.at("/_embedded").has("author")).isFalse();
        // a relation with an embeddable and a non-embeddable link: [] at the non-embeddable position
        assertThat(node.at("/_embedded/up/1").toString()).isEqualTo("[]");
        assertThat(node.at("/_embedded/up")).hasSize(2);
        // two links with the same href: the handler ran once for it
        assertThat(node.at("/_embedded/related/0")).isEqualTo(node.at("/_embedded/up/0"));
        assertThat(calls("1")).isEqualTo(1);
        // the embedded item has its links (self with target hints) but nothing embedded further
        JsonNode embedded = node.at("/_embedded/up/0");
        assertThat(fieldNames(embedded)).containsExactly("id", "context", "_links");
        assertThat(embedded.at("/_links/self/0/targetHints/allow/0").asText()).isEqualTo("GET");
        assertThat(embedded.at("/_links/up/0/embeddable").toString()).isEqualTo("true");
        assertThat(calls("2")).isZero();
        assertThat(calls("7")).isZero();
        // Part D 18: ?_embed=0 embeds as ?_embed does; a listed relation embeds only that one
        assertThat(json(Http.as(null).get(NODES + "/3?_embed=0").asString())).isEqualTo(node);
        JsonNode up = json(Http.as(null).get(NODES + "/3?_embed=up").asString());
        assertThat(fieldNames(up.get("_embedded"))).containsExactly("up");
        // ?_embed=author (whose only link is not embeddable): no "_embedded" key at all
        JsonNode author = json(Http.as(null).get(NODES + "/3?_embed=author").asString());
        assertThat(fieldNames(author)).containsExactly("id", "context", "_links");
    }

    // Part D 18 could not record an embedded error (unreachable in a default WordPress install): this test keeps the
    // reference's reading, and the parity run cannot compare it
    @Test
    void errorsAreEmbeddedWithoutChangingTheParent() {
        Http.as(null)
                .get(NODES + "/4?_embed=up")
                .then()
                .statusCode(200)
                .body("id", equalTo(4))
                .body("_embedded.up[0].code", equalTo("rest_forbidden"))
                .body("_embedded.up[0].message", equalTo("Sorry, you are not allowed to do that."))
                .body("_embedded.up[0].data.status", equalTo(401));
        assertThat(calls("9")).isZero();
        // the embedded answer is what the caller gets for the href itself
        Http.as(null).get(NODES + "/9").then().statusCode(401).body("code", equalTo("rest_forbidden"));
        // a caller allowed to read it gets it embedded
        Http.as("test-" + administrator)
                .get(NODES + "/4?_embed=up")
                .then()
                .statusCode(200)
                .body("_embedded.up[0].id", equalTo(9))
                .body("_embedded.up[0].context", equalTo("embed"));
        assertThat(calls("9")).isEqualTo(1);
    }

    @Test
    void perPageIsRaisedToTheRoutesMaximumAndCollectionsNeedLinksRequested() {
        JsonNode sizes = json(Http.as(null).get(NODES + "/5?_embed=wp:sizes").asString());
        // per_page absent: the route's maximum; given: kept; context given: kept, else embed
        assertThat(sizes.at("/_embedded/wp:sizes/0/0").toString())
                .isEqualTo("{\"per_page\":100,\"context\":\"embed\"}");
        assertThat(sizes.at("/_embedded/wp:sizes/1/0").toString()).isEqualTo("{\"per_page\":2,\"context\":\"embed\"}");
        assertThat(sizes.at("/_embedded/wp:sizes/2/0").toString()).isEqualTo("{\"per_page\":100,\"context\":\"view\"}");
        // Part D 16: a collection's items keep their links only when _links is requested, and only then embed
        assertThat(Http.as(null).get(NODES + "?_fields=id,_embedded&_embed").asString())
                .isEqualTo("[{\"id\":3},{\"id\":4}]");
        assertThat(calls("1")).isZero();
        JsonNode items =
                json(Http.as(null).get(NODES + "?_fields=id,_links&_embed").asString());
        assertThat(fieldNames(items.get(0))).containsExactly("id", "_links", "_embedded");
        assertThat(items.at("/0/_embedded/up/0/id").toString()).isEqualTo("1");
        assertThat(items.at("/1/_embedded/up/0/code").asText()).isEqualTo("rest_forbidden");
        // the cache spans the items of one answer: node 1 ran once for both of node 3's links
        assertThat(calls("1")).isEqualTo(1);
        // an embedded item is reduced by its own request, never by the outer _fields
        assertThat(fieldNames(items.at("/0/_embedded/up/0"))).containsExactly("id", "context", "_links");
        // Part D 16, single item: the controller adds its links for _embedded, the server keeps them past _fields
        JsonNode single =
                json(Http.as(null).get(NODES + "/3?_fields=id,_embedded&_embed").asString());
        assertThat(fieldNames(single)).containsExactly("id", "_links", "_embedded");
        assertThat(single.at("/_embedded/up/0/id").toString()).isEqualTo("1");
        // _embed in a JSON body is ignored: only the query string asks for embedding
        String bodyOnly = Http.as(null)
                .contentType("application/json")
                .body("{\"_embed\":\"1\"}")
                .get(NODES + "/3")
                .asString();
        assertThat(json(bodyOnly).has("_embedded")).isFalse();
    }

    @Test
    void embeddingStaysOnThisSite() {
        int before = calls("1");
        JsonNode node =
                json(Http.as(null).get(NODES + "/5?_embed=elsewhere,local").asString());
        // links to another host, or through another host's rest_route, are never followed
        assertThat(node.at("/_embedded").has("elsewhere")).isFalse();
        // this site's rest_route form is followed, as WordPress follows it
        assertThat(node.at("/_embedded/local/0/id").toString()).isEqualTo("1");
        assertThat(calls("1")).isEqualTo(before + 1);
    }

    @Test
    void linksAndEmbedsLandInsideTheEnvelopeAndHeadEmbedsNothing() {
        JsonNode envelope =
                json(Http.as(null).get(NODES + "/3?_embed=up&_envelope").asString());
        assertThat(envelope.at("/status").toString()).isEqualTo("200");
        assertThat(fieldNames(envelope.get("body"))).endsWith("_links", "_embedded");
        assertThat(envelope.at("/body/_embedded/up/0/id").toString()).isEqualTo("1");
        assertThat(calls("1")).isEqualTo(1);
        Response head = Http.as(null).head(NODES + "/3?_embed");
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(calls("1")).isEqualTo(1);
    }

    private static List<String> hints(String caller, String url) {
        return texts(
                json(Http.as(caller).get(url).then().statusCode(200).extract().asString())
                        .at("/_links/self/0/targetHints/allow"));
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(item -> texts.add(item.asText()));
        return texts;
    }

    private static int calls(String id) {
        AtomicInteger count = TestRoutes.NODE_CALLS.get(id);
        return count == null ? 0 : count.get();
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
