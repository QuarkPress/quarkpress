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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * Who may write terms, and what a member of another site sees, on a network, with WordPress's codes and statuses:
 * categories are written by those who manage categories (the administrator and the editor), tags are created by those
 * who assign them (also the author and the contributor) and changed or deleted only by those who manage categories.
 * Every refusal is 401 for an anonymous caller and 403 for a logged-in one, and leaves the term as it was.
 *
 * <p>The outsider frank is the administrator of another site and has no role on the main site: there he writes no
 * term and is refused the edit context of terms, types, taxonomies, statuses and users, the settings, and the e-mail
 * column of a users search, while on his own site he has an administrator's rights.
 */
class TermWritersDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(TermWritersDbTest.class);

    static final String CANNOT_CREATE = "Sorry, you are not allowed to create terms in this taxonomy.";
    static final String CANNOT_UPDATE = "Sorry, you are not allowed to edit this term.";
    static final String CANNOT_DELETE = "Sorry, you are not allowed to delete this term.";

    AgroalDataSource data;
    String u;

    /** The callers on the main site, by name: null is anonymous. */
    final Map<String, String> callers = new LinkedHashMap<>();

    long administrator;
    long editor;
    long carol;
    long frank;
    String franksHost;

    @BeforeEach
    void network() {
        data = Jdbc.dataSource();
        u = UUID.randomUUID().toString().substring(0, 8);
        administrator = TestData.memberOfMain(data, "administrator").value();
        editor = TestData.memberOfMain(data, "editor").value();
        carol = TestData.profile(data, "carol-" + u, "Carol Chen", "carol-" + u + "@quarkpress.test")
                .value();
        TestData.member(data, TestData.MAIN, UserId.of(carol), "[\"author\"]");
        // a published post makes carol visible to anyone here
        TestData.post(data, TestData.MAIN, UserId.of(carol), "post", "publish");
        long dave = TestData.memberOfMain(data, "contributor").value();
        long erin = TestData.memberOfMain(data, "subscriber").value();
        SiteId franks = TestData.site(data);
        franksHost = Jdbc.strings(data, "select domain from qp_site where id = ?", franks.value())
                .get(0);
        frank = TestData.profile(data, "frank").value();
        TestData.member(data, franks, UserId.of(frank), "[\"administrator\"]");
        callers.clear();
        callers.put("anonymous", null);
        callers.put("administrator", "test-" + administrator);
        callers.put("editor", "test-" + editor);
        callers.put("author", "test-" + carol);
        callers.put("contributor", "test-" + dave);
        callers.put("subscriber", "test-" + erin);
        callers.put("outsider", "test-" + frank);
    }

    @Test
    void termsAreCreatedByWhoManagesOrAssignsThem() {
        for (Map.Entry<String, String> caller : callers.entrySet()) {
            String who = caller.getKey();
            boolean managesCategories = who.equals("administrator") || who.equals("editor");
            boolean assignsTags = managesCategories || who.equals("author") || who.equals("contributor");
            create(caller, "categories", managesCategories, managesCategories);
            create(caller, "tags", assignsTags, managesCategories);
        }
    }

    @Test
    void termsAreChangedAndDeletedOnlyByWhoManagesCategories() {
        for (String base : List.of("categories", "tags")) {
            for (Map.Entry<String, String> caller : callers.entrySet()) {
                String who = caller.getKey();
                boolean manages = who.equals("administrator") || who.equals("editor");
                long term = term(base, "Kept " + who + " " + u);
                Response updated = Http.as(caller.getValue())
                        .contentType("application/json")
                        .body("{\"name\":\"Changed " + who + " " + u + "\"}")
                        .post("/wp-json/wp/v2/" + base + "/" + term);
                Response deleted =
                        Http.as(caller.getValue()).delete("/wp-json/wp/v2/" + base + "/" + term + "?force=true");
                if (manages) {
                    updated.then().statusCode(200).body("name", equalTo("Changed " + who + " " + u));
                    deleted.then().statusCode(200).body("deleted", equalTo(true));
                    Http.as(null)
                            .get("/wp-json/wp/v2/" + base + "/" + term)
                            .then()
                            .statusCode(404);
                } else {
                    int status = caller.getValue() == null ? 401 : 403;
                    expect(updated, status, "rest_cannot_update", CANNOT_UPDATE, base + " update as " + who);
                    expect(deleted, status, "rest_cannot_delete", CANNOT_DELETE, base + " delete as " + who);
                    // the term is as it was
                    Http.as(null)
                            .get("/wp-json/wp/v2/" + base + "/" + term)
                            .then()
                            .statusCode(200)
                            .body("name", equalTo("Kept " + who + " " + u));
                }
            }
        }
    }

    @Test
    void anotherSitesAdministratorIsRefusedTheEditContextsHere() {
        String outsider = "test-" + frank;
        long category = term("categories", "Edit " + u);
        expect(
                Http.as(outsider).get("/wp-json/wp/v2/categories?context=edit"),
                403,
                "rest_forbidden_context",
                "Sorry, you are not allowed to edit terms in this taxonomy.",
                "categories");
        expect(
                Http.as(outsider).get("/wp-json/wp/v2/categories/" + category + "?context=edit"),
                403,
                "rest_forbidden_context",
                CANNOT_UPDATE,
                "category");
        String types = "Sorry, you are not allowed to edit posts in this post type.";
        expect(Http.as(outsider).get("/wp-json/wp/v2/types?context=edit"), 403, "rest_cannot_view", types, "types");
        expect(
                Http.as(outsider).get("/wp-json/wp/v2/types/post?context=edit"),
                403,
                "rest_forbidden_context",
                types,
                "types/post");
        String taxonomies = "Sorry, you are not allowed to manage terms in this taxonomy.";
        expect(
                Http.as(outsider).get("/wp-json/wp/v2/taxonomies?context=edit"),
                403,
                "rest_cannot_view",
                taxonomies,
                "taxonomies");
        expect(
                Http.as(outsider).get("/wp-json/wp/v2/taxonomies/category?context=edit"),
                403,
                "rest_forbidden_context",
                taxonomies,
                "taxonomies/category");
        expect(
                Http.as(outsider).get("/wp-json/wp/v2/statuses?context=edit"),
                403,
                "rest_cannot_view",
                "Sorry, you are not allowed to manage post statuses.",
                "statuses");
        expect(
                Http.as(outsider).get("/wp-json/wp/v2/settings"),
                403,
                "rest_forbidden",
                "Sorry, you are not allowed to do that.",
                "settings");
        // the same requests on frank's own site are an administrator's
        for (String route : List.of(
                "categories?context=edit",
                "types?context=edit",
                "types/post?context=edit",
                "taxonomies?context=edit",
                "taxonomies/category?context=edit",
                "statuses?context=edit",
                "settings")) {
            Http.at(franksHost, outsider).get("/wp-json/wp/v2/" + route).then().statusCode(200);
        }
    }

    @Test
    void anotherSitesAdministratorSeesNoMemberDetailsHere() {
        String outsider = "test-" + frank;
        expect(
                Http.as(outsider).get("/wp-json/wp/v2/users?context=edit"),
                403,
                "rest_forbidden_context",
                "Sorry, you are not allowed to edit users.",
                "users");
        expect(
                Http.as(outsider).get("/wp-json/wp/v2/users/" + carol + "?context=edit"),
                403,
                "rest_forbidden_context",
                "Sorry, you are not allowed to edit this user.",
                "users/carol");
        // frank sees carol, who has a published post here, but the e-mail column is not searched for him: carol is
        // found by her address only by who lists users
        String byEmail =
                "/wp-json/wp/v2/users?search=carol-" + u + "@quarkpress.test&search_columns[]=email&include=" + carol;
        Response searched = Http.as(outsider).get(byEmail);
        searched.then().statusCode(200).header("X-WP-Total", "0");
        assertThat(ids(searched)).isEmpty();
        assertThat(ids(Http.as("test-" + administrator).get(byEmail))).containsExactly(carol);
        assertThat(ids(Http.as(outsider).get("/wp-json/wp/v2/users?search=Chen&include=" + carol)))
                .containsExactly(carol);
        Http.as(outsider)
                .get("/wp-json/wp/v2/users/" + carol)
                .then()
                .statusCode(200)
                .body("id", equalTo((int) carol));
    }

    /**
     * Creates a term of {@code base} as {@code caller} and checks the answer: 201, or WordPress's refusal. A created
     * term is answered in the edit context, and its {@code Allow} computed in it: GET only for who may edit terms.
     */
    private void create(Map.Entry<String, String> caller, String base, boolean allowed, boolean edits) {
        String name = "New " + caller.getKey() + " " + u;
        Response created = Http.as(caller.getValue())
                .contentType("application/json")
                .body("{\"name\":\"" + name + "\"}")
                .post("/wp-json/wp/v2/" + base);
        String what = base + " create as " + caller.getKey();
        if (allowed) {
            created.then()
                    .statusCode(201)
                    .header("Allow", edits ? "GET, POST" : "POST")
                    .body("name", equalTo(name));
            return;
        }
        expect(created, caller.getValue() == null ? 401 : 403, "rest_cannot_create", CANNOT_CREATE, what);
        Http.as(null)
                .get("/wp-json/wp/v2/" + base + "?search=" + caller.getKey() + "%20" + u)
                .then()
                .statusCode(200)
                .header("X-WP-Total", "0");
    }

    /** A term of {@code base} named {@code name}, created by the administrator. */
    private long term(String base, String name) {
        return Http.as("test-" + administrator)
                .contentType("application/json")
                .body("{\"name\":\"" + name + "\"}")
                .post("/wp-json/wp/v2/" + base)
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    private static void expect(Response response, int status, String code, String message, String what) {
        JsonNode body = json(response.then().extract().asString());
        assertThat(response.statusCode()).as(what).isEqualTo(status);
        assertThat(body.path("code").asText()).as(what).isEqualTo(code);
        assertThat(body.path("message").asText()).as(what).isEqualTo(message);
        assertThat(body.at("/data/status").isIntegralNumber()).as(what).isTrue();
        assertThat(body.at("/data/status").longValue()).as(what).isEqualTo(status);
    }

    private static List<Long> ids(Response response) {
        JsonNode list = json(response.then().statusCode(200).extract().asString());
        List<Long> ids = new ArrayList<>();
        list.forEach(user -> ids.add(user.get("id").longValue()));
        return ids;
    }

    private static JsonNode json(String text) {
        try {
            return RestJson.READER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
