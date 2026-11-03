package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * A single post ({@code GET /wp/v2/posts/<id>}, pages and the custom type's books alike): the lookup and the checks
 * in WordPress's order, password protection and what lifts it, the view, edit and embed contexts, the derived
 * fields (permalink template, generated slug, class list), the template rule and the alternate {@code Link}. The
 * site's zone is Europe/Paris and its permalink structure names the post first, so an all-digit slug reads as a date
 * archive; members: an administrator, the editor bob, the author carol, the contributor dave and the subscriber erin.
 */
class PostItemDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            RestApp.network(PostItemDbTest.class, BookType.class, PostFixtures.class, PostFixtures.Builder.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long administrator;
    static long bob;
    static long carol;
    static long dave;
    static long erin;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.siteIn(data, "Europe/Paris");
        Jdbc.option(site, "permalink_structure", "\"/%postname%/\"");
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        administrator = member("administrator");
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

    /** A request to the test site as the user with {@code bearer}, or anonymous when null. */
    static RequestSpecification as(String bearer) {
        return Http.at(host, bearer);
    }

    private static PostFixtures.Builder post(String status, long author, String title) {
        boolean floating = status.equals("draft") || status.equals("pending") || status.equals("auto-draft");
        return PostFixtures.builder(site)
                .status(status)
                .author(author)
                .title(title)
                .publishedAt(
                        floating ? null : status.equals("future") ? "2030-03-01T10:00:00Z" : "2026-03-01T10:00:00Z")
                .modifiedAt("2026-03-01T10:00:00Z");
    }

    private static long term(String taxonomy, String slug) {
        Jdbc.update(
                data,
                "insert into qp_term (site_id, taxonomy, name, slug, description) values (?, ?, ?, ?, '')",
                site.value(),
                taxonomy,
                slug,
                slug);
        return Jdbc.termId(site, taxonomy, slug);
    }

    private static void assign(PostId post, long term) {
        Jdbc.update(
                data,
                "insert into qp_term_assignment (site_id, post_id, term_id) values (?, ?, ?)",
                site.value(),
                post.value(),
                term);
    }

    private static void meta(PostId post, String json) {
        Jdbc.update(data, "update qp_post set meta = " + TestDb.json() + " where id = ?", json, post.value());
    }

    @Test
    void lookupsAndPermissionsInWordPressOrder() {
        String u = UUID.randomUUID().toString();
        PostId published = post("publish", carol, "published " + u).insert(data);
        PostId aPage = post("publish", carol, "page " + u).type("page").insert(data);
        PostId future = post("future", carol, "future " + u).insert(data);
        PostId bobsDraft = post("draft", bob, "draft " + u).insert(data);
        PostId carolsTrashed = post("trash", carol, "trashed " + u).insert(data);
        as(null).get("/wp-json/wp/v2/posts/999999")
                .then()
                .statusCode(404)
                .header("Allow", nullValue())
                .body("code", equalTo("rest_post_invalid_id"))
                .body("message", equalTo("Invalid post ID."));
        as(null).get("/wp-json/wp/v2/posts/0").then().statusCode(404).body("code", equalTo("rest_post_invalid_id"));
        // another type
        as(null).get("/wp-json/wp/v2/posts/" + aPage.value())
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_id"));
        as(null).get("/wp-json/wp/v2/pages/" + aPage.value()).then().statusCode(200);
        as(null).get("/wp-json/wp/v2/posts/" + future.value())
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_forbidden"))
                .body("message", equalTo("Sorry, you are not allowed to do that."));
        as("test-" + carol)
                .get("/wp-json/wp/v2/posts/" + bobsDraft.value())
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_forbidden"));
        as("test-" + carol)
                .get("/wp-json/wp/v2/posts/" + carolsTrashed.value())
                .then()
                .statusCode(200)
                .body("status", equalTo("trash"));
        as("test-" + dave)
                .get("/wp-json/wp/v2/posts/" + published.value() + "?context=edit")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_forbidden_context"))
                .body("message", equalTo("Sorry, you are not allowed to edit this post."));
        as(null).get("/wp-json/wp/v2/posts/" + published.value() + "?context=edit")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_forbidden_context"));
        // the edit-context check comes before the password check, which comes before the read check
        as(null).get("/wp-json/wp/v2/posts/" + future.value() + "?context=edit&password=no")
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_forbidden_context"));
        as(null).get("/wp-json/wp/v2/posts/" + future.value() + "?password=no")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_post_incorrect_password"));
        as(null).get("/wp-json/wp/v2/posts/" + published.value())
                .then()
                .statusCode(200)
                .header("Allow", equalTo("GET"))
                .body("id", equalTo((int) published.value()));
    }

    @Test
    void anotherSitesPostIsMissing() {
        SiteId other = TestData.site(data);
        PostId theirs = PostFixtures.builder(other).title("another site's post").insert(data);
        as(null).get("/wp-json/wp/v2/posts/" + theirs.value())
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_id"));
        as("test-" + administrator)
                .get("/wp-json/wp/v2/posts/" + theirs.value() + "?context=edit")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_id"));
    }

    @Test
    void passwordProtection() {
        String u = UUID.randomUUID().toString();
        PostId secret = post("publish", carol, "secret " + u)
                .content("Secret words " + u)
                .excerpt("")
                .password("s3")
                .insert(data);
        PostId open =
                post("publish", carol, "open " + u).content("Open words " + u).insert(data);
        String item = "/wp-json/wp/v2/posts/" + secret.value();
        JsonNode hidden =
                json(as(null).get(item).then().statusCode(200).extract().asString());
        assertThat(hidden.at("/content/rendered").asText()).isEmpty();
        assertThat(hidden.at("/content/protected").asBoolean()).isTrue();
        assertThat(hidden.at("/excerpt/rendered").asText()).isEmpty();
        assertThat(hidden.at("/excerpt/protected").asBoolean()).isTrue();
        assertThat(hidden.at("/title/rendered").asText()).isEqualTo("secret " + u);
        assertThat(hidden.get("class_list").toString()).contains("\"post-password-required\"");
        // the matching password renders the content; the flags and the class list still say it is protected
        JsonNode lifted = json(as(null).get(item + "?password=s3")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(lifted.at("/content/rendered").asText()).isEqualTo("Secret words " + u);
        assertThat(lifted.at("/content/protected").asBoolean()).isTrue();
        assertThat(lifted.at("/excerpt/rendered").asText()).isEqualTo("Secret words " + u);
        assertThat(lifted.at("/excerpt/protected").asBoolean()).isTrue();
        assertThat(lifted.get("class_list").toString()).contains("\"post-password-required\"");
        assertThat(lifted.get("class_list").toString()).doesNotContain("post-password-protected");
        // a wrong query-string password refuses, with no Allow for an anonymous caller
        as(null).get(item + "?password=no")
                .then()
                .statusCode(403)
                .header("Allow", nullValue())
                .body("code", equalTo("rest_post_incorrect_password"))
                .body("message", equalTo("Incorrect post password."))
                .body("data.status", equalTo(403));
        // for a caller who may edit the post, WordPress's Allow lists the writing methods and never GET: the
        // writers come with their routes, so for now the header has nothing to list
        Response refused = as("test-" + administrator).get(item + "?password=no");
        refused.then().statusCode(403).body("code", equalTo("rest_post_incorrect_password"));
        assertThat(refused.header("Allow") == null || !refused.header("Allow").contains("GET"))
                .as("Allow " + refused.header("Allow"))
                .isTrue();
        // a password on a post without one refuses too
        as(null).get("/wp-json/wp/v2/posts/" + open.value() + "?password=x")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_post_incorrect_password"));
        as("test-" + administrator)
                .get("/wp-json/wp/v2/posts/" + open.value() + "?password=x")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_post_incorrect_password"));
        // an empty password is ignored and lifts nothing
        as(null).get(item + "?password=")
                .then()
                .statusCode(200)
                .body("content.rendered", equalTo(""))
                .body("content.protected", equalTo(true));
        // the edit context of a caller who may edit lifts it and shows the password; a wrong password still refuses
        JsonNode edit = json(as("test-" + administrator)
                .get(item + "?context=edit")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(edit.get("password").asText()).isEqualTo("s3");
        assertThat(edit.at("/content/raw").asText()).isEqualTo("Secret words " + u);
        assertThat(edit.at("/content/rendered").asText()).isEqualTo("Secret words " + u);
        assertThat(edit.at("/content/protected").asBoolean()).isTrue();
        assertThat(edit.get("class_list").toString()).contains("\"post-password-required\"");
        as("test-" + administrator)
                .get(item + "?context=edit&password=no")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_post_incorrect_password"));
        // the view context lifts nothing, administrator or not
        as("test-" + administrator)
                .get(item)
                .then()
                .statusCode(200)
                .body("content.rendered", equalTo(""))
                .body("content.protected", equalTo(true));
        // a password in the JSON body of a GET lifts but never refuses
        as(null).contentType("application/json")
                .body("{\"password\":\"s3\"}")
                .get(item)
                .then()
                .statusCode(200)
                .body("content.rendered", equalTo("Secret words " + u));
        as(null).contentType("application/json")
                .body("{\"password\":\"no\"}")
                .get(item)
                .then()
                .statusCode(200)
                .body("content.rendered", equalTo(""));
        // a stored password "0" is no password, as for WordPress: nothing is hidden, flagged or listed as required
        PostId zero = post("publish", carol, "zero " + u)
                .content("Zero words " + u)
                .password("0")
                .insert(data);
        JsonNode unprotected = json(as(null).get("/wp-json/wp/v2/posts/" + zero.value())
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(unprotected.at("/content/rendered").asText()).isEqualTo("Zero words " + u);
        assertThat(unprotected.at("/content/protected").asBoolean()).isFalse();
        assertThat(unprotected.at("/excerpt/protected").asBoolean()).isFalse();
        assertThat(unprotected.get("class_list").toString()).doesNotContain("post-password");
        assertThat(edit("test-" + administrator, "posts", zero).get("password").asText())
                .isEqualTo("0");
        // the collection reads the password too: it lifts the matching items and never refuses
        JsonNode listed = json(as(null).get("/wp-json/wp/v2/posts?password=s3&include=" + secret.value() + ","
                        + open.value() + "&orderby=include")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(listed.size()).isEqualTo(2);
        assertThat(listed.at("/0/content/rendered").asText()).isEqualTo("Secret words " + u);
        assertThat(listed.at("/0/content/protected").asBoolean()).isTrue();
        assertThat(listed.at("/1/content/rendered").asText()).isEqualTo("Open words " + u);
        assertThat(listed.at("/1/content/protected").asBoolean()).isFalse();
        JsonNode wrong = json(as(null).get("/wp-json/wp/v2/posts?password=no&include=" + secret.value())
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(wrong.at("/0/content/rendered").asText()).isEmpty();
    }

    @Test
    void derivedFields() {
        String u = UUID.randomUUID().toString();
        PostId helloThere = post("draft", carol, "Hello There").slug("").insert(data);
        PostId hello = post("publish", carol, "Hello").slug("hello").insert(data);
        PostId secondHello = post("draft", carol, "Hello").slug("").insert(data);
        PostId untitled = post("draft", carol, "").slug("").insert(data);
        PostId foo = post("publish", carol, "Bar").slug("foo").insert(data);
        PostId page = post("publish", carol, "A page " + u).type("page").insert(data);
        PostId book = post("publish", carol, "A book " + u)
                .type("book")
                .slug("a-book")
                .insert(data);
        PostId defaultTemplate =
                post("publish", carol, "Default template " + u).type("page").insert(data);
        meta(defaultTemplate, "{\"_wp_page_template\":[\"default\"]}");
        PostId goneTemplate =
                post("publish", carol, "Gone template " + u).type("page").insert(data);
        meta(goneTemplate, "{\"_wp_page_template\":[\"gone-template\"]}");
        String admin = "test-" + administrator;
        assertThat(edit(admin, "posts", helloThere).get("generated_slug").asText())
                .isEqualTo("hello-there");
        assertThat(edit(admin, "posts", hello).get("generated_slug").asText()).isEqualTo("hello");
        assertThat(edit(admin, "posts", secondHello).get("generated_slug").asText())
                .isEqualTo("hello-2");
        assertThat(edit(admin, "posts", untitled).get("generated_slug").asText())
                .isEqualTo(untitled.value() + "-2");
        assertThat(edit(admin, "posts", foo).get("generated_slug").asText()).isEqualTo("bar");
        JsonNode draft = edit(admin, "posts", helloThere);
        assertThat(draft.get("permalink_template").asText()).isEqualTo("http://" + host + "/?p=" + helloThere.value());
        assertThat(draft.get("permalink_template").asText())
                .isEqualTo(draft.get("link").asText());
        JsonNode pageItem = edit(admin, "pages", page);
        assertThat(pageItem.get("permalink_template").asText())
                .isEqualTo("http://" + host + "/?page_id=" + page.value());
        assertThat(pageItem.get("generated_slug").asText()).isEqualTo("a-page-" + u);
        assertThat(pageItem.get("template").asText()).isEmpty();
        assertThat(edit(admin, "pages", defaultTemplate).get("template").asText())
                .isEmpty();
        assertThat(edit(admin, "pages", goneTemplate).get("template").asText()).isEqualTo("gone-template");
        // a public custom type has the derived fields in the edit context, none in the view context
        JsonNode bookItem = edit(admin, "books", book);
        assertThat(bookItem.get("permalink_template").asText()).isEqualTo("http://" + host + "/?book=a-book");
        assertThat(bookItem.get("generated_slug").asText()).isEqualTo("a-book-" + u);
        assertThat(bookItem.get("class_list").toString())
                .isEqualTo("[\"post-" + book.value() + "\",\"book\",\"type-book\",\"status-publish\",\"hentry\"]");
        JsonNode bookView = json(as(null).get("/wp-json/wp/v2/books/" + book.value())
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(bookView.has("permalink_template")).isFalse();
        assertThat(bookView.has("generated_slug")).isFalse();
        assertThat(bookView.has("class_list")).isTrue();
        // the class list of a formatted, tagged post
        PostId aside = post("publish", carol, "Aside " + u).insert(data);
        long news = term("category", "news-" + u);
        long numeric = term("post_tag", "123");
        long format = term("post_format", "post-format-aside");
        assign(aside, news);
        assign(aside, numeric);
        assign(aside, format);
        JsonNode asideItem = json(as(null).get("/wp-json/wp/v2/posts/" + aside.value())
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(asideItem.get("format").asText()).isEqualTo("aside");
        assertThat(asideItem.get("class_list").toString())
                .isEqualTo("[\"post-" + aside.value() + "\",\"post\",\"type-post\",\"status-publish\",\"format-aside\","
                        + "\"hentry\",\"category-news-" + u + "\",\"tag-" + numeric + "\","
                        + "\"post_format-post-format-aside\"]");
        // the alternate Link of an item is the only Link line, on HEAD too; the collection keeps the root's
        Response item = as(null).get("/wp-json/wp/v2/posts/" + hello.value());
        assertThat(item.headers().getValues("Link"))
                .containsExactly("<http://" + host + "/?p=" + hello.value() + ">; rel=\"alternate\"; type=text/html");
        Response head = as(null).head("/wp-json/wp/v2/posts/" + hello.value());
        head.then().statusCode(200);
        assertThat(head.headers().getValues("Link"))
                .containsExactly("<http://" + host + "/?p=" + hello.value() + ">; rel=\"alternate\"; type=text/html");
        assertThat(as(null).get("/wp-json/wp/v2/pages/" + page.value())
                        .headers()
                        .getValues("Link"))
                .containsExactly(
                        "<http://" + host + "/?page_id=" + page.value() + ">; rel=\"alternate\"; type=text/html");
        assertThat(as(null).get("/wp-json/wp/v2/books/" + book.value())
                        .headers()
                        .getValues("Link"))
                .containsExactly("<http://" + host + "/?book=a-book>; rel=\"alternate\"; type=text/html");
        assertThat(as(null).get("/wp-json/wp/v2/posts?include=" + hello.value())
                        .headers()
                        .getValues("Link"))
                .containsExactly("<http://" + host + "/wp-json/>; rel=\"https://api.w.org/\"");
    }

    @Test
    void embedAndEditContexts() {
        String u = UUID.randomUUID().toString();
        PostId post = post("publish", carol, "Contexts " + u)
                .content("<!-- wp:paragraph --><p>Body</p><!-- /wp:paragraph -->")
                .insert(data);
        PostId page = post("publish", carol, "Page contexts " + u).type("page").insert(data);
        String admin = "test-" + administrator;
        JsonNode view = json(as(null).get("/wp-json/wp/v2/posts/" + post.value())
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(fieldNames(view))
                .containsExactly(
                        "id",
                        "date",
                        "date_gmt",
                        "guid",
                        "modified",
                        "modified_gmt",
                        "slug",
                        "status",
                        "type",
                        "link",
                        "title",
                        "content",
                        "excerpt",
                        "author",
                        "featured_media",
                        "comment_status",
                        "ping_status",
                        "sticky",
                        "template",
                        "format",
                        "meta",
                        "categories",
                        "tags",
                        "class_list",
                        "_links");
        assertThat(fieldNames(view.get("guid"))).containsExactly("rendered");
        assertThat(fieldNames(view.get("title"))).containsExactly("rendered");
        assertThat(fieldNames(view.get("content"))).containsExactly("rendered", "protected");
        assertThat(fieldNames(view.get("excerpt"))).containsExactly("rendered", "protected");
        JsonNode edit = edit(admin, "posts", post);
        assertThat(fieldNames(edit))
                .containsExactly(
                        "id",
                        "date",
                        "date_gmt",
                        "guid",
                        "modified",
                        "modified_gmt",
                        "password",
                        "slug",
                        "status",
                        "type",
                        "link",
                        "title",
                        "content",
                        "excerpt",
                        "author",
                        "featured_media",
                        "comment_status",
                        "ping_status",
                        "sticky",
                        "template",
                        "format",
                        "meta",
                        "categories",
                        "tags",
                        "permalink_template",
                        "generated_slug",
                        "class_list",
                        "_links");
        assertThat(fieldNames(edit.get("guid"))).containsExactly("rendered", "raw");
        assertThat(fieldNames(edit.get("title"))).containsExactly("raw", "rendered");
        assertThat(fieldNames(edit.get("content"))).containsExactly("raw", "rendered", "protected", "block_version");
        assertThat(edit.at("/content/block_version").asInt()).isEqualTo(1);
        assertThat(fieldNames(edit.get("excerpt"))).containsExactly("raw", "rendered", "protected");
        assertThat(edit.get("password").asText()).isEmpty();
        JsonNode embed = json(as(null).get("/wp-json/wp/v2/posts/" + post.value() + "?context=embed")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(fieldNames(embed))
                .containsExactly(
                        "id", "date", "slug", "type", "link", "title", "excerpt", "author", "featured_media", "_links");
        assertThat(fieldNames(embed.get("excerpt"))).containsExactly("rendered", "protected");
        JsonNode pageView = json(as(null).get("/wp-json/wp/v2/pages/" + page.value())
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(fieldNames(pageView))
                .containsExactly(
                        "id",
                        "date",
                        "date_gmt",
                        "guid",
                        "modified",
                        "modified_gmt",
                        "slug",
                        "status",
                        "type",
                        "link",
                        "title",
                        "content",
                        "excerpt",
                        "author",
                        "featured_media",
                        "parent",
                        "menu_order",
                        "comment_status",
                        "ping_status",
                        "template",
                        "meta",
                        "class_list",
                        "_links");
        JsonNode pageEdit = edit(admin, "pages", page);
        assertThat(fieldNames(pageEdit))
                .containsExactly(
                        "id",
                        "date",
                        "date_gmt",
                        "guid",
                        "modified",
                        "modified_gmt",
                        "password",
                        "slug",
                        "status",
                        "type",
                        "link",
                        "title",
                        "content",
                        "excerpt",
                        "author",
                        "featured_media",
                        "parent",
                        "menu_order",
                        "comment_status",
                        "ping_status",
                        "template",
                        "meta",
                        "permalink_template",
                        "generated_slug",
                        "class_list",
                        "_links");
        JsonNode pageEmbed = json(as(null).get("/wp-json/wp/v2/pages/" + page.value() + "?context=embed")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(fieldNames(pageEmbed))
                .containsExactly(
                        "id", "date", "slug", "type", "link", "title", "excerpt", "author", "featured_media", "_links");
    }

    private static JsonNode edit(String bearer, String base, PostId id) {
        return json(as(bearer)
                .get("/wp-json/wp/v2/" + base + "/" + id.value() + "?context=edit")
                .then()
                .statusCode(200)
                .extract()
                .asString());
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
