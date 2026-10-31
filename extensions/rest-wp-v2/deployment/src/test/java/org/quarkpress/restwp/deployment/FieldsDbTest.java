package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.QuarkusExtensionTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * WordPress's {@code _fields} at both levels: the controller builds only the requested fields of its schema (and
 * always {@code id}), then the server reduces the answer to the requested key tree, quirks included (Part D 16).
 */
class FieldsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(FieldsDbTest.class, TestRoutes.class);

    @Test
    void anItemKeepsOnlyTheRequestedFields() {
        // the controller computes id and method; the server keeps what was asked for
        body(
                "/wp-json/qp-test/v1/things/3",
                "{\"method\":\"GET\",\"id\":3,\"_links\":{\"self\":[{\"href\":"
                        + "\"http:\\/\\/localhost\\/wp-json\\/qp-test\\/v1\\/things\\/3\",\"targetHints\":{\"allow\":[\"GET\"]}}]}}");
        body("/wp-json/qp-test/v1/things/3?_fields=id", "{\"id\":3}");
        body("/wp-json/qp-test/v1/things/3?_fields=method", "{\"method\":\"GET\"}");
        body("/wp-json/qp-test/v1/things/3?_fields=nothing", "[]");
        // the item's links are attached after the reduction, and only when _links is requested
        Http.as(null)
                .get("/wp-json/qp-test/v1/things/3?_fields=id,_links")
                .then()
                .statusCode(200)
                .body("id", equalTo(3))
                .body("_links.self[0].href", equalTo("http://localhost/wp-json/qp-test/v1/things/3"));
    }

    @Test
    void bothWordPressQuirksAreKept() {
        // a parent requested before one of its children stops the list there (Part D 16: name,name.x,id gives name)
        body("/wp-json/qp-test/v1/things/3?_fields=method,method.x,id", "{\"method\":\"GET\"}");
        body("/wp-json/qp-test/v1/things/3?_fields=method.x,method,id", "{\"method\":\"GET\",\"id\":3}");
        // a child of a scalar keeps the scalar whole
        body("/wp-json/qp-test/v1/things/3?_fields=id.x", "{\"id\":3}");
    }

    @Test
    void collectionsAreReducedItemByItem() {
        body("/wp-json/qp-test/v1/things?_fields=id&per_page=2", "[{\"id\":1},{\"id\":2}]");
        body("/wp-json/qp-test/v1/items?_fields=x", "[[],[]]");
        Http.as(null)
                .get("/wp-json/qp-test/v1/things?_fields=id&per_page=2")
                .then()
                .header("X-WP-Total", "3")
                .header(
                        "Link",
                        "<http://localhost/wp-json/qp-test/v1/things?_fields=id&per_page=2&page=2>; rel=\"next\"");
    }

    @Test
    void errorsAreNotReduced() {
        Http.as(null)
                .get("/wp-json/qp-test/v1/guarded?per_page=0&_fields=code")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("message", equalTo("Invalid parameter(s): per_page"))
                .body("data.status", equalTo(400))
                .body("data.params.per_page", notNullValue());
        Http.as(null)
                .get("/wp-json/qp-test/v1/none?_fields=code")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_no_route"))
                .body("message", equalTo("No route was found matching the URL and request method."))
                .body("data.status", equalTo(404));
        Http.as(null)
                .get("/wp-json/qp-test/v1/denied?_fields=code")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_view"))
                .body("message", equalTo("Nope."))
                .body("data.status", equalTo(401));
    }

    @Test
    void fieldsComeFromAnySourceInPrecedence() {
        String json = Http.as(null)
                .contentType("application/json")
                .body("{\"_fields\":\"method,id\"}")
                .post("/wp-json/qp-test/v1/echo/5?_fields=params")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(json).isEqualTo("{\"method\":\"POST\",\"id\":\"5\"}");
        assertThat(Http.as(null)
                        .formParam("_fields", "urlId")
                        .post("/wp-json/qp-test/v1/echo/5")
                        .asString())
                .isEqualTo("{\"urlId\":\"5\"}");
        // an array is taken item by item, without splitting
        assertThat(Http.as(null)
                        .get("/wp-json/qp-test/v1/echo/5?_fields[]=method&_fields[]=urlId,id")
                        .asString())
                .isEqualTo("{\"method\":\"GET\"}");
    }

    @Test
    void theReductionLandsInsideTheEnvelope() {
        JsonNode envelope = json(Http.as(null)
                .get("/wp-json/qp-test/v1/things/3?_fields=id&_envelope")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(envelope.get("body").toString()).isEqualTo("{\"id\":3}");
        assertThat(envelope.get("status").asInt()).isEqualTo(200);
        assertThat(envelope.at("/headers/Allow").asText()).isEqualTo("GET");
    }

    static void body(String url, String expected) {
        assertThat(Http.as(null).get(url).then().statusCode(200).extract().asString())
                .as(url)
                .isEqualTo(expected);
    }

    static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
