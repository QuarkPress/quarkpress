package org.quarkword.it.front;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The HTTP calls the front tests share, with the Keycloak client: setup through {@code /it/*} and the REST API as a
 * realm user, page requests as an anonymous visitor. Every request names {@code Host: localhost}, the host of the
 * site's {@code home}, and follows no redirect. In an integration test, Quarkus hands the Dev Services context to the
 * test's fields of this type, which pass it on to the Keycloak client.
 */
class FrontCalls implements DevServicesContext.ContextAware {

    /** Obtains tokens from the Dev Services Keycloak (in the IT, Quarkus hands it the Dev Services context). */
    private final KeycloakTestClient keycloak = new KeycloakTestClient();

    @Override
    public void setIntegrationTestContext(DevServicesContext context) {
        keycloak.setIntegrationTestContext(context);
    }

    /** A request as {@code user} (a Bearer token), or an anonymous one for null. */
    RequestSpecification as(String user) {
        RequestSpecification request =
                given().header("Host", "localhost").redirects().follow(false);
        return user == null ? request : request.auth().oauth2(keycloak.getAccessToken(user));
    }

    /** An anonymous request. */
    RequestSpecification visitor() {
        return as(null);
    }

    /** A token that makes a slug or title unique in this run. */
    static String token() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** Sets the site's option {@code name} to the JSON value {@code json}, as alice (the site's administrator). */
    void option(String name, String json) {
        as("alice")
                .contentType(ContentType.JSON)
                .body(json)
                .put("/it/options/" + name)
                .then()
                .statusCode(200);
    }

    /** Sets the site's text option {@code name}. */
    void textOption(String name, String value) {
        option(name, "\"" + value + "\"");
    }

    /** A post of {@code type} written by alice with its fields; answers its id. */
    long post(String type, String status, String title, Map<String, Object> fields) {
        return as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("type", type, "status", status, "title", title, "fields", fields))
                .post("/it/posts")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    /** Changes the post's slug as alice. */
    void slug(long post, String slug) {
        as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("slug", slug))
                .put("/it/posts/" + post)
                .then()
                .statusCode(200);
    }

    /** A category made by alice; answers its id and slug. */
    Map<String, Object> category(String name) {
        return as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("name", name))
                .post("/it/terms/category")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap("$");
    }

    /** A text file uploaded by alice, attached to nothing; answers the attachment's id. */
    long upload(String fileName, String content) {
        return as("alice")
                .multiPart("file", fileName, content.getBytes(StandardCharsets.UTF_8), "text/plain")
                .post("/it/media")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    /**
     * Opens a preview session for {@code user} through the REST API; answers the {@code name=value} pair of its
     * cookie, which the front end reads.
     */
    String previewSession(String user) {
        Response answer = as(user).post("/wp-json/quarkword/v1/preview/session");
        answer.then().statusCode(200);
        return cookiePair(answer, "quarkword_preview");
    }

    /** The {@code name=value} pair of the answer's single cookie named {@code name}. */
    static String cookiePair(Response answer, String name) {
        List<String> lines = answer.headers().getValues("Set-Cookie").stream()
                .filter(line -> line.startsWith(name + "="))
                .toList();
        assertThat(lines).as("Set-Cookie %s", name).hasSize(1);
        String line = lines.get(0);
        int end = line.indexOf(';');
        return end < 0 ? line : line.substring(0, end);
    }

    /** The path and query of an absolute URL of the site ({@code http://localhost/x/?a=1} → {@code /x/?a=1}). */
    static String pathOf(String url) {
        assertThat(url).startsWith(FrontDbTest.HOME + "/");
        return url.substring(FrontDbTest.HOME.length());
    }
}
