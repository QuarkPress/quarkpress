package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

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
 * {@code POST /quarkpress/v1/drafts}: the auto-draft a new post starts from, as WordPress's new-post screen creates
 * it (the row, its edit-context representation, the default post format), its refusals, and that no lock is taken.
 * Members of the site: the author carol, the editor bob, the contributor dave; the site's zone is UTC.
 */
class DraftsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            RestApp.network(DraftsDbTest.class, BookType.class, PostFixtures.class, PostFixtures.Builder.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DRAFTS = "/wp-json/quarkpress/v1/drafts";

    private static final String CANNOT_CREATE = "Sorry, you are not allowed to create posts as this user.";

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long carol;
    static long bob;
    static long dave;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.site(data);
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        carol = member("carol", "Carol Chen", "carol@example.com", "author");
        bob = member("bob", "Bob Baker", "bob@example.com", "editor");
        dave = member("dave", "Dave Diaz", "dave@example.com", "contributor");
    }

    private static long member(String nicename, String displayName, String email, String role) {
        UserId user = TestData.profile(data, nicename, displayName, email);
        TestData.member(data, site, user, "[\"" + role + "\"]");
        return user.value();
    }

    @Test
    void createsWordPresssAutoDraft() {
        Response draft = draft(carol, "post");
        long id = draft.jsonPath().getLong("id");
        draft.then()
                .statusCode(201)
                .header("Location", equalTo("http://" + host + "/wp-json/wp/v2/posts/" + id))
                .body("status", equalTo("auto-draft"))
                .body("type", equalTo("post"))
                .body("title.raw", equalTo("Auto Draft"))
                .body("title.rendered", equalTo("Auto Draft"))
                .body("content.raw", equalTo(""))
                .body("excerpt.raw", equalTo(""))
                .body("generated_slug", equalTo("auto-draft"))
                .body("slug", equalTo(""))
                .body("password", equalTo(""))
                .body("author", equalTo((int) carol))
                .body("categories", equalTo(List.of()))
                .body("tags", equalTo(List.of()))
                .body("format", equalTo("standard"))
                .body("sticky", equalTo(false))
                .body("template", equalTo(""))
                .body("meta.footnotes", equalTo(""))
                .body("link", equalTo("http://" + host + "/?p=" + id))
                .body("guid.raw", equalTo("http://" + host + "/?p=" + id))
                .body("comment_status", equalTo("open"))
                .body("ping_status", equalTo("open"))
                // the plain-permalink form of WordPress's %postname% template
                .body("permalink_template", equalTo("http://" + host + "/?p=" + id));
        // the floating local date: date, date_gmt, modified and modified_gmt are one time
        JsonNode body = json(draft);
        String date = body.get("date").textValue();
        assertThat(date).hasSize(19);
        assertThat(body.get("date_gmt").textValue()).isEqualTo(date);
        assertThat(body.get("modified").textValue()).isEqualTo(date);
        assertThat(body.get("modified_gmt").textValue()).isEqualTo(date);
        // the row as WordPress inserts it: no category, no revision, nothing but the auto-draft
        assertThat(Jdbc.strings(data, "select status from qp_post where id = ?", id))
                .containsExactly("auto-draft");
        assertThat(Jdbc.strings(data, "select title from qp_post where id = ?", id))
                .containsExactly("Auto Draft");
        assertThat(Jdbc.strings(data, "select slug from qp_post where id = ?", id))
                .containsExactly("");
        // the date floats: no GMT date on the row, only the API view computes one
        assertThat(Jdbc.count(data, "select count(*) from qp_post where id = ? and published_at is null", id))
                .isEqualTo(1);
        assertThat(Jdbc.count(data, "select count(*) from qp_post where parent_id = ?", id))
                .isZero();
        assertThat(Jdbc.count(data, "select count(*) from qp_term_assignment where post_id = ?", id))
                .isZero();
        // a second one: its own row; the generated slug does not count up, as no auto-draft holds the slug
        Response second = draft(carol, "post");
        second.then()
                .statusCode(201)
                .body("generated_slug", equalTo("auto-draft"))
                .body("slug", equalTo(""));
        assertThat(second.jsonPath().getLong("id")).isGreaterThan(id);

        // a page: the type's comment defaults, template, parent and menu order, and the page link
        Response page = draft(bob, "page");
        long pageId = page.jsonPath().getLong("id");
        page.then()
                .statusCode(201)
                .header("Location", equalTo("http://" + host + "/wp-json/wp/v2/pages/" + pageId))
                .body("status", equalTo("auto-draft"))
                .body("type", equalTo("page"))
                .body("title.raw", equalTo("Auto Draft"))
                .body("author", equalTo((int) bob))
                .body("comment_status", equalTo("closed"))
                .body("ping_status", equalTo("closed"))
                .body("template", equalTo(""))
                .body("parent", equalTo(0))
                .body("menu_order", equalTo(0))
                .body("link", equalTo("http://" + host + "/?page_id=" + pageId))
                .body("permalink_template", equalTo("http://" + host + "/?page_id=" + pageId))
                .body("generated_slug", equalTo("auto-draft"));
        assertThat(json(page).has("format")).isFalse();
        assertThat(json(page).has("categories")).isFalse();

        // a registered type with a title: "Auto Draft" too, at its own base
        Response book = draft(carol, "book");
        long bookId = book.jsonPath().getLong("id");
        book.then()
                .statusCode(201)
                .header("Location", equalTo("http://" + host + "/wp-json/wp/v2/books/" + bookId))
                .body("type", equalTo("book"))
                .body("title.raw", equalTo("Auto Draft"))
                .body("link", equalTo("http://" + host + "/?post_type=book&p=" + bookId));
    }

    @Test
    void theDraftIsReadAsAnyPostTheCallerMayEdit() {
        long id = draft(carol, "post").jsonPath().getLong("id");
        // the editor reads it in the view context
        as(bob).get("/wp-json/wp/v2/posts/" + id)
                .then()
                .statusCode(200)
                .body("status", equalTo("auto-draft"))
                .body("title.rendered", equalTo("Auto Draft"));
        as(carol)
                .get("/wp-json/wp/v2/posts/" + id + "?context=edit")
                .then()
                .statusCode(200)
                .body("title.raw", equalTo("Auto Draft"));
        as(null).get("/wp-json/wp/v2/posts/" + id)
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_forbidden"))
                .body("data.status", equalTo(401));
        as(dave).get("/wp-json/wp/v2/posts/" + id)
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_forbidden"))
                .body("data.status", equalTo(403));
    }

    @Test
    void refusals() {
        // without the type's edit_posts and create_posts: a contributor may start a post but not a page
        draft(dave, "post").then().statusCode(201).body("author", equalTo((int) dave));
        long before = autoDrafts();
        draft(dave, "page")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_create"))
                .body("message", equalTo(CANNOT_CREATE))
                .body("data.status", equalTo(403));
        draft(null, "post")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_create"))
                .body("message", equalTo(CANNOT_CREATE))
                .body("data.status", equalTo(401));
        // an unknown type, and the types other controllers own
        for (String type : List.of("nope", "attachment", "wp_block", "revision", "")) {
            draft(bob, type)
                    .then()
                    .statusCode(404)
                    .body("code", equalTo("rest_type_invalid"))
                    .body("message", equalTo("Invalid post type."))
                    .body("data.status", equalTo(404));
        }
        // nobody learns which types exist: the unknown type is 404 for the anonymous caller too
        draft(null, "nope").then().statusCode(404).body("code", equalTo("rest_type_invalid"));
        // the type is required
        as(bob).contentType("application/json")
                .body("{}")
                .post(DRAFTS)
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_missing_callback_param"))
                .body("message", equalTo("Missing parameter(s): type"))
                .body("data.params", equalTo(List.of("type")));
        as(bob).contentType("application/json")
                .body("{\"type\":5}")
                .post(DRAFTS)
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"));
        // nothing was written by the refusals
        assertThat(autoDrafts()).isEqualTo(before);
    }

    private static long autoDrafts() {
        return Jdbc.count(
                data, "select count(*) from qp_post where site_id = ? and status = 'auto-draft'", site.value());
    }

    @Test
    void defaultPostFormatApplies() {
        Jdbc.option(site, "default_post_format", "\"aside\"");
        try {
            Response post = draft(carol, "post");
            post.then()
                    .statusCode(201)
                    .body("format", equalTo("aside"))
                    .body("class_list", hasItems("format-aside", "post_format-post-format-aside"));
            long id = post.jsonPath().getLong("id");
            assertThat(Jdbc.count(data, "select count(*) from qp_term_assignment where post_id = ?", id))
                    .isEqualTo(1);
            // a type without post formats takes none
            Response page = draft(bob, "page");
            page.then().statusCode(201);
            assertThat(json(page).has("format")).isFalse();
            assertThat(Jdbc.count(
                            data,
                            "select count(*) from qp_term_assignment where post_id = ?",
                            page.jsonPath().getLong("id")))
                    .isZero();
            // WordPress's default "0" and a format that is not one: the standard format
            Jdbc.option(site, "default_post_format", "\"0\"");
            draft(carol, "post").then().statusCode(201).body("format", equalTo("standard"));
            Jdbc.option(site, "default_post_format", "\"nope\"");
            draft(carol, "post").then().statusCode(201).body("format", equalTo("standard"));
        } finally {
            Jdbc.deleteOption(site, "default_post_format");
        }
    }

    @Test
    void noLockIsTaken() {
        long id = draft(carol, "post").jsonPath().getLong("id");
        String meta =
                Jdbc.strings(data, "select meta from qp_post where id = ?", id).get(0);
        assertThat(json(meta).has("_edit_lock")).isFalse();
        assertThat(json(meta).has("_edit_last")).isFalse();
    }

    private static Response draft(Long user, String type) {
        return as(user).contentType("application/json")
                .body("{\"type\":\"" + type + "\"}")
                .post(DRAFTS);
    }

    /** A request to the test site as the user with this id, or anonymous when null. */
    private static RequestSpecification as(Long user) {
        return Http.at(host, user == null ? null : "test-" + user);
    }

    private static JsonNode json(Response response) {
        return json(response.asString());
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
