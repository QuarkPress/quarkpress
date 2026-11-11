package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * The terms routes of WordPress's pattern categories ({@code /wp/v2/wp_pattern_category}), served unchanged: the
 * anonymous caller lists them, anyone who may assign them ({@code edit_posts}) creates one in this flat taxonomy,
 * deleting needs {@code manage_categories}, a term links in the plain form (the taxonomy has no query variable nor
 * rewrite) and to the synced patterns that have it. Members of the site (UTC): alice (administrator and super admin),
 * the editor bob, the author carol, the contributor dave and the subscriber erin.
 */
class PatternCategoryTermsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(PatternCategoryTermsDbTest.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ROUTE = "/wp-json/wp/v2/wp_pattern_category";

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long alice;
    static long bob;
    static long carol;
    static long dave;
    static long erin;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.siteIn(data, "UTC");
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        alice = member("administrator");
        TestData.superAdmin(data, UserId.of(alice));
        bob = member("editor");
        carol = member("author");
        dave = member("contributor");
        erin = member("subscriber");
    }

    private static long member(String role) {
        UserId user = TestData.profile(data, role);
        TestData.member(data, site, user, "[\"" + role + "\"]");
        return user.value();
    }

    static RequestSpecification as(long user) {
        return Http.at(host, user == 0 ? null : "test-" + user).contentType("application/json");
    }

    static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long create(long user, String name) {
        return json(as(user).body("{\"name\":\"" + name + "\"}")
                        .post(ROUTE)
                        .then()
                        .statusCode(201)
                        .extract()
                        .asString())
                .get("id")
                .asLong();
    }

    @Test
    void anonymousListsAndAuthorsCreate() {
        long hero = create(alice, "Hero");
        Response listed = as(0).get(ROUTE);
        listed.then().statusCode(200).header("Allow", "GET");
        assertThat(listed.asString()).contains("\"slug\":\"hero\"");
        as(0).get(ROUTE + "/" + hero).then().statusCode(200).header("Allow", "GET");
        as(carol).get(ROUTE).then().statusCode(200).header("Allow", "GET, POST");
        // a flat taxonomy: assigning is enough to create
        long daves = create(dave, "Dave " + hero);
        as(erin).body("{\"name\":\"Erin\"}")
                .post(ROUTE)
                .then()
                .statusCode(403)
                .header("Allow", "GET")
                .body(equalTo("{\"code\":\"rest_cannot_create\",\"message\":\"Sorry, you are not allowed to create"
                        + " terms in this taxonomy.\",\"data\":{\"status\":403}}"));
        as(0).body("{\"name\":\"Nobody\"}")
                .post(ROUTE)
                .then()
                .statusCode(401)
                .header("Allow", "GET")
                .body(equalTo("{\"code\":\"rest_cannot_create\",\"message\":\"Sorry, you are not allowed to create"
                        + " terms in this taxonomy.\",\"data\":{\"status\":401}}"));
        // a duplicate names the existing term, as the editor needs
        as(dave).body("{\"name\":\"Hero\"}")
                .post(ROUTE)
                .then()
                .statusCode(400)
                .body("code", equalTo("term_exists"))
                .body("message", equalTo("A term with the name provided already exists in this taxonomy."))
                .body("data.status", equalTo(400))
                .body("data.term_id", equalTo((int) hero))
                .body("additional_data", equalTo(List.of((int) hero, (int) hero)));
        // deleting needs manage_categories
        as(carol)
                .delete(ROUTE + "/" + daves + "?force=true")
                .then()
                .statusCode(403)
                .header("Allow", "GET")
                .body(equalTo("{\"code\":\"rest_cannot_delete\",\"message\":\"Sorry, you are not allowed to delete"
                        + " this term.\",\"data\":{\"status\":403}}"));
        as(bob).delete(ROUTE + "/" + daves + "?force=true")
                .then()
                .statusCode(200)
                .body("deleted", equalTo(true));
    }

    @Test
    void termLinksArePlain() {
        long buttons = create(bob, "Buttons");
        JsonNode term = json(as(0).get(ROUTE + "/" + buttons).asString());
        assertThat(term.get("link").asText())
                .isEqualTo("http://" + host + "/?taxonomy=wp_pattern_category&term=buttons");
        assertThat(term.get("taxonomy").asText()).isEqualTo("wp_pattern_category");
        assertThat(term.has("parent")).isFalse();
        assertThat(term.at("/_links/wp:post_type/0/href").asText())
                .isEqualTo("http://" + host + "/wp-json/wp/v2/blocks?wp_pattern_category=" + buttons);
        assertThat(term.at("/_links/about/0/href").asText())
                .isEqualTo("http://" + host + "/wp-json/wp/v2/taxonomies/wp_pattern_category");
    }
}
