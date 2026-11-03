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
 * The post collections of every REST post type ({@code /wp/v2/posts}, {@code /wp/v2/pages} and the custom type's
 * {@code /wp/v2/books}): which types get routes, the permission-checked {@code status} sanitizer, the callback errors
 * of the orderings, paging past the end and the offset's window, the exclusive date filters read in the site's zone,
 * the sticky parameters, WordPress's list filters, HEAD, hostile lists, the totals of readable rows and the fields
 * per type and context. The site's zone is Europe/Paris; members: an administrator, an editor, the author carol, the
 * contributor dave and the subscriber erin.
 */
class PostsCollectionDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
            PostsCollectionDbTest.class, BookType.class, PostFixtures.class, PostFixtures.Builder.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long administrator;
    static long editor;
    static long carol;
    static long dave;
    static long erin;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.siteIn(data, "Europe/Paris");
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        administrator = member("administrator");
        editor = member("editor");
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

    @Test
    void contentRoutesExistForRestTypesOnly() {
        as(null).get("/wp-json/wp/v2/posts").then().statusCode(200).header("Allow", "GET");
        as(null).get("/wp-json/wp/v2/pages").then().statusCode(200);
        as(null).get("/wp-json/wp/v2/books").then().statusCode(200);
        as(null).get("/wp-json/wp/v2/blocks").then().statusCode(404).body("code", equalTo("rest_no_route"));
        as(null).get("/wp-json/wp/v2/media").then().statusCode(404).body("code", equalTo("rest_no_route"));
    }

    @Test
    void theStatusParameterIsAPermissionCheckedSanitizer() {
        as(null).get("/wp-json/wp/v2/posts?status=draft")
                .then()
                .statusCode(400)
                .header("Allow", "GET")
                .body("code", equalTo("rest_invalid_param"))
                .body("message", equalTo("Invalid parameter(s): status"))
                .body("data.params.status", equalTo("Status is forbidden."))
                .body("data.details.status.code", equalTo("rest_forbidden_status"))
                .body("data.details.status.message", equalTo("Status is forbidden."))
                .body("data.details.status.data.status", equalTo(401));
        as("test-" + erin)
                .get("/wp-json/wp/v2/posts?status=private")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("data.details.status.code", equalTo("rest_forbidden_status"))
                .body("data.details.status.data.status", equalTo(403));
        as("test-" + editor)
                .get("/wp-json/wp/v2/posts?status=bogus")
                .then()
                .statusCode(400)
                .body(
                        "data.params.status",
                        equalTo("status[0] is not one of publish, future, draft, pending,"
                                + " private, trash, auto-draft, inherit, request-pending, request-confirmed, request-failed,"
                                + " request-completed, and any."))
                .body("data.details.status.code", equalTo("rest_not_in_enum"));
        // the sanitizer is a slug list: upper case and spaces are WordPress's title sanitizing
        String u = UUID.randomUUID().toString();
        PostId published = post("publish", "s " + u, "2026-02-01T10:00:00Z");
        PostId scheduled = PostFixtures.builder(site)
                .status("future")
                .author(carol)
                .title("f " + u)
                .publishedAt("2030-02-01T10:00:00Z")
                .modifiedAt("2026-02-01T10:00:01Z")
                .insert(data);
        PostId draft = PostFixtures.builder(site)
                .status("draft")
                .author(carol)
                .title("d " + u)
                .publishedAt(null)
                .modifiedAt("2026-02-01T10:00:02Z")
                .insert(data);
        assertThat(ids(as("test-" + editor)
                        .get("/wp-json/wp/v2/posts?status=Publish,future&search=" + u + "&_fields=id")))
                .containsExactlyInAnyOrder(published.value(), scheduled.value());
        assertThat(ids(as("test-" + administrator).get("/wp-json/wp/v2/posts?search=" + u + "&_fields=id")))
                .containsExactly(published.value());
        assertThat(ids(as("test-" + carol).get("/wp-json/wp/v2/posts?status=draft&search=" + u + "&_fields=id")))
                .containsExactly(draft.value());
    }

    @Test
    void aCallerWithReadPrivatePostsAloneIsRefusedByTheSanitizerNotTheQuery() {
        // the posts module lists a status other than publish only to a caller with the type's edit_posts, so the
        // sanitizer asks for the same: the refusal is the sanitizer's 400, never the query's 403
        UserId reader = TestData.profile(data, "reader");
        Jdbc.update(
                data,
                "insert into qp_site_membership (site_id, user_id, roles, capabilities) values (?, ?, " + TestDb.json()
                        + ", " + TestDb.json() + ")",
                site.value(),
                reader.value(),
                "[]",
                "{\"read_private_posts\":true,\"read\":true}");
        as("test-" + reader.value())
                .get("/wp-json/wp/v2/posts?status=private")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("data.details.status.code", equalTo("rest_forbidden_status"))
                .body("data.details.status.data.status", equalTo(403));
        as("test-" + reader.value()).get("/wp-json/wp/v2/posts").then().statusCode(200);
    }

    @Test
    void orderingErrorsAreCallbackErrors() {
        as(null).get("/wp-json/wp/v2/posts?orderby=relevance")
                .then()
                .statusCode(400)
                .header("Allow", "GET")
                .body("code", equalTo("rest_no_search_term_defined"))
                .body("message", equalTo("You need to define a search term to order by relevance."))
                .body("data.status", equalTo(400));
        as(null).get("/wp-json/wp/v2/posts?orderby=include")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_orderby_include_missing_include"))
                .body("message", equalTo("You need to define an include parameter to order by include."))
                .body("data.status", equalTo(400));
    }

    @Test
    void pagesPastTheEnd() {
        String u = UUID.randomUUID().toString();
        PostId first = post("publish", "pg1 " + u, "2026-03-01T10:00:00Z");
        PostId second = post("publish", "pg2 " + u, "2026-03-01T10:00:01Z");
        PostId third = post("publish", "pg3 " + u, "2026-03-01T10:00:02Z");
        String base = "/wp-json/wp/v2/posts?search=" + u + "&_fields=id";
        as(null).get(base + "&per_page=2&page=3")
                .then()
                .statusCode(400)
                .header("Allow", "GET")
                .body("code", equalTo("rest_post_invalid_page_number"))
                .body("message", equalTo("The page number requested is larger than the number of pages available."))
                .body("data.status", equalTo(400));
        // nothing found (a search no book matches): 200 [] with totals 0 and a prev link to page 0
        as(null).get("/wp-json/wp/v2/books?page=2&search=" + u)
                .then()
                .statusCode(200)
                .header("X-WP-Total", "0")
                .header("X-WP-TotalPages", "0")
                .header("Link", "<http://" + host + "/wp-json/wp/v2/books?page=0&search=" + u + ">; rel=\"prev\"")
                .body("", equalTo(List.of()));
        // the offset moves the window; the page parameter only moves the links
        Response window = as(null).get(base + "&per_page=1&offset=1");
        window.then()
                .statusCode(200)
                .header("X-WP-Total", "3")
                .header("X-WP-TotalPages", "3")
                .header(
                        "Link",
                        "<http://" + host + "/wp-json/wp/v2/posts?search=" + u
                                + "&_fields=id&per_page=1&offset=1&page=2>; rel=\"next\"");
        assertThat(ids(window)).containsExactly(second.value());
        Response paged = as(null).get(base + "&per_page=1&offset=1&page=2");
        paged.then()
                .statusCode(200)
                .header("X-WP-Total", "3")
                .header("X-WP-TotalPages", "3")
                .header(
                        "Link",
                        "<http://" + host + "/wp-json/wp/v2/posts?search=" + u
                                + "&_fields=id&per_page=1&offset=1&page=1>; rel=\"prev\", <http://" + host
                                + "/wp-json/wp/v2/posts?search=" + u + "&_fields=id&per_page=1&offset=1&page=3>;"
                                + " rel=\"next\"");
        assertThat(ids(paged)).containsExactly(second.value());
        // an offset at the total: WordPress's totals are 0
        as(null).get(base + "&per_page=1&offset=3")
                .then()
                .statusCode(200)
                .header("X-WP-Total", "0")
                .header("X-WP-TotalPages", "0")
                .header("Link", "<http://" + host + "/wp-json/>; rel=\"https://api.w.org/\"")
                .body("", equalTo(List.of()));
        // an empty search is no condition
        assertThat(ids(as(null).get("/wp-json/wp/v2/posts?search=&per_page=100&_fields=id")))
                .contains(first.value(), second.value(), third.value());
        as(null).get(base + "&per_page=2&page=0")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("data.params.page", equalTo("page must be greater than or equal to 1"))
                .body("data.details.page.code", equalTo("rest_out_of_bounds"));
    }

    @Test
    void datesAreExclusiveAndReadInTheSiteZone() {
        String u = UUID.randomUUID().toString();
        // local Europe/Paris 2026-01-15 11:00:00 and 11:00:01 (winter: UTC+1)
        PostId first = PostFixtures.builder(site)
                .author(carol)
                .title("dt1 " + u)
                .publishedAt("2026-01-15T10:00:00Z")
                .modifiedAt("2026-01-15T10:00:00Z")
                .insert(data);
        PostId second = PostFixtures.builder(site)
                .author(carol)
                .title("dt2 " + u)
                .publishedAt("2026-01-15T10:00:01Z")
                .modifiedAt("2026-01-15T10:00:01Z")
                .insert(data);
        String base = "/wp-json/wp/v2/posts?search=" + u + "&_fields=id";
        assertThat(ids(as(null).get(base + "&after=2026-01-15T10:00:00Z"))).containsExactly(second.value());
        assertThat(ids(as(null).get(base + "&after=2026-01-15T11:00:00"))).containsExactly(second.value());
        assertThat(ids(as(null).get(base + "&before=2026-01-15T11:00:01"))).containsExactly(first.value());
        assertThat(ids(as(null).get(base + "&before=2026-01-15T12:00:01+02:00")))
                .containsExactly(first.value());
        assertThat(ids(as(null).get(base + "&modified_after=2026-01-15T10:00:00Z")))
                .containsExactly(second.value());
        assertThat(ids(as(null).get(base + "&modified_before=2026-01-15T11:00:01")))
                .containsExactly(first.value());
        assertThat(ids(as(null).get(base + "&after=2026-01-15T11:00:01"))).isEmpty();
        as(null).get(base + "&after=yesterday")
                .then()
                .statusCode(400)
                .body("data.params.after", equalTo("Invalid date."))
                .body("data.details.after.code", equalTo("rest_invalid_date"));
    }

    @Test
    void stickyParameters() {
        String u = UUID.randomUUID().toString();
        PostId s1 = PostFixtures.builder(site)
                .author(carol)
                .title("s1 " + u)
                .publishedAt("2026-04-01T10:00:00Z")
                .sticky()
                .insert(data);
        PostId n1 = post("publish", "n1 " + u, "2026-04-01T10:00:01Z");
        PostId s2 = PostFixtures.builder(site)
                .author(carol)
                .title("s2 " + u)
                .publishedAt("2026-04-01T10:00:02Z")
                .sticky()
                .insert(data);
        PostId n2 = post("publish", "n2 " + u, "2026-04-01T10:00:03Z");
        String base = "/wp-json/wp/v2/posts?search=" + u + "&_fields=id";
        assertThat(ids(as(null).get(base + "&sticky=true"))).containsExactly(s2.value(), s1.value());
        assertThat(ids(as(null).get(base + "&sticky=false"))).containsExactly(n2.value(), n1.value());
        // a search has no sticky-first in WordPress (the stickies come first only on a home-like query), so the
        // ignore_sticky cases select this test's posts by their dates (local Europe/Paris, summer time)
        Response first = as(null).get("/wp-json/wp/v2/posts?ignore_sticky=false&per_page=2&_fields=id" + "&include="
                + s1.value() + "," + n1.value() + "," + s2.value() + "," + n2.value());
        // include fills post__in, which forces the stickies to be ignored: the page is the plain first page
        assertThat(ids(first)).containsExactly(n2.value(), s2.value());
        Response page1 = as(null).get("/wp-json/wp/v2/posts?ignore_sticky=false&per_page=2&_fields=id&after="
                + "2026-04-01T11:59:59&before=2026-04-01T12:00:04");
        page1.then().statusCode(200).header("X-WP-Total", "4").header("X-WP-TotalPages", "2");
        assertThat(ids(page1)).containsExactly(s2.value(), s1.value(), n2.value());
        Response page2 = as(null).get("/wp-json/wp/v2/posts?ignore_sticky=false&per_page=2&_fields=id&after="
                + "2026-04-01T11:59:59&before=2026-04-01T12:00:04&page=2");
        page2.then().statusCode(200).header("X-WP-Total", "4").header("X-WP-TotalPages", "2");
        assertThat(ids(page2)).containsExactly(n1.value(), s1.value());
        as(null).get(base + "&sticky=true&include=" + n1.value())
                .then()
                .statusCode(200)
                .header("X-WP-Total", "0")
                .body("", equalTo(List.of()));
        as(null).get("/wp-json/wp/v2/pages?sticky=true").then().statusCode(200);
    }

    @Test
    void listFiltersFollowWordPress() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        PostId a = PostFixtures.builder(site)
                .author(carol)
                .slug("slug-a-" + u)
                .title("lf a " + u)
                .publishedAt("2026-05-01T10:00:00Z")
                .insert(data);
        PostId b = PostFixtures.builder(site)
                .author(editor)
                .slug("slug-b-" + u)
                .title("lf b " + u)
                .publishedAt("2026-05-01T10:00:01Z")
                .insert(data);
        PostId c = PostFixtures.builder(site)
                .author(editor)
                .slug("slug-c-" + u)
                .title("lf c " + u)
                .excerpt("needle" + u)
                .publishedAt("2026-05-01T10:00:02Z")
                .insert(data);
        String base = "/wp-json/wp/v2/posts?search=lf " + u + "&_fields=id";
        assertThat(ids(as(null).get(base))).as("search alone").containsExactly(c.value(), b.value(), a.value());
        assertThat(ids(as(null).get("/wp-json/wp/v2/posts?_fields=id&slug=slug-a-" + u + ",Slug-B-" + u)))
                .as("slug alone")
                .containsExactly(b.value(), a.value());
        assertThat(ids(as(null).get(base + "&slug=slug-a-" + u + ",Slug-B-" + u)))
                .containsExactly(b.value(), a.value());
        assertThat(ids(as(null).get("/wp-json/wp/v2/posts?_fields=id&include=" + a.value() + "," + c.value()
                        + "&orderby=include")))
                .containsExactly(a.value(), c.value());
        assertThat(ids(as(null).get("/wp-json/wp/v2/posts?_fields=id&include=" + a.value() + "," + c.value()
                        + "&exclude=" + a.value())))
                .containsExactly(c.value(), a.value());
        assertThat(ids(as(null).get(base + "&author=" + carol + "&author_exclude=" + carol)))
                .containsExactly(c.value(), b.value());
        assertThat(ids(as(null).get(base + "&author=" + carol))).containsExactly(a.value());
        PostId parent = PostFixtures.builder(site)
                .type("page")
                .author(editor)
                .title("lf parent " + u)
                .publishedAt("2026-05-01T10:00:03Z")
                .insert(data);
        PostId child = PostFixtures.builder(site)
                .type("page")
                .author(editor)
                .title("lf child " + u)
                .parent(parent)
                .menuOrder(3)
                .publishedAt("2026-05-01T10:00:04Z")
                .insert(data);
        assertThat(ids(as(null).get("/wp-json/wp/v2/pages?_fields=id&parent=" + parent.value())))
                .containsExactly(child.value());
        assertThat(ids(as(null).get("/wp-json/wp/v2/pages?_fields=id&search=lf " + u + "&menu_order=3")))
                .containsExactly(child.value());
        assertThat(ids(as(null).get("/wp-json/wp/v2/pages?_fields=id&search=lf " + u + "&parent_exclude="
                        + parent.value())))
                .containsExactly(parent.value());
        assertThat(ids(as(null).get(
                                "/wp-json/wp/v2/posts?_fields=id&search=needle" + u + "&search_columns=post_excerpt")))
                .containsExactly(c.value());
        assertThat(ids(as(null).get(
                                "/wp-json/wp/v2/posts?_fields=id&search=needle" + u + "&search_columns=post_title")))
                .isEmpty();
        // the exact semantics: each term must be a whole column
        PostId whole = PostFixtures.builder(site)
                .author(carol)
                .title("Needle Title " + u)
                .publishedAt("2026-05-01T10:00:05Z")
                .insert(data);
        PostId terms = PostFixtures.builder(site)
                .author(carol)
                .title("Needle")
                .excerpt("Title")
                .content(u)
                .publishedAt("2026-05-01T10:00:06Z")
                .insert(data);
        assertThat(ids(as(null).get("/wp-json/wp/v2/posts?_fields=id&search=Needle Title " + u
                        + "&search_semantics=exact")))
                .containsExactly(terms.value());
        assertThat(ids(as(null).get("/wp-json/wp/v2/posts?_fields=id&search=Needle Title " + u)))
                .containsExactly(terms.value(), whole.value());
        // unknown orderings are refused by the enum; the slug ordering is the post name
        assertThat(ids(as(null).get(base + "&orderby=slug&order=asc")))
                .containsExactly(a.value(), b.value(), c.value());
        assertThat(ids(as(null).get(base + "&orderby=id"))).containsExactly(c.value(), b.value(), a.value());
    }

    @Test
    void headCountsOnly() {
        String u = UUID.randomUUID().toString();
        post("publish", "h1 " + u, "2026-06-01T10:00:00Z");
        post("publish", "h2 " + u, "2026-06-01T10:00:01Z");
        Response head = as(null).head("/wp-json/wp/v2/posts?search=" + u + "&per_page=1");
        head.then()
                .statusCode(200)
                .header("X-WP-Total", "2")
                .header("X-WP-TotalPages", "2")
                .header("Allow", "GET");
        assertThat(head.asString()).isEmpty();
    }

    @Test
    void hostileListsAreRefusedLikeWordPress() {
        // lists that long exceed the HTTP server's request line: they travel in a JSON body
        StringBuilder include = new StringBuilder();
        for (int i = 1; i <= 1001; i++) {
            include.append(i == 1 ? "" : ",").append(i);
        }
        as(null).header("X-HTTP-Method-Override", "GET")
                .contentType("application/json")
                .body("{\"include\":[" + include + "]}")
                .post("/wp-json/wp/v2/posts")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("data.params.include", equalTo("include must contain at most 1000 items."));
        StringBuilder statuses = new StringBuilder();
        for (int i = 1; i <= 1001; i++) {
            statuses.append(i == 1 ? "" : ",").append("\"publish\"");
        }
        as("test-" + editor)
                .header("X-HTTP-Method-Override", "GET")
                .contentType("application/json")
                .body("{\"status\":[" + statuses + "]}")
                .post("/wp-json/wp/v2/posts")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("data.params.status", equalTo("status must contain at most 1000 items."));
        String u = UUID.randomUUID().toString();
        PostId visible = post("publish", "hostile " + u, "2026-06-02T10:00:00Z");
        // a query string that long is refused by the HTTP server before the REST API: the search goes in a JSON body
        String search = "x".repeat(10_000);
        Response answer = as(null).header("X-HTTP-Method-Override", "GET")
                .contentType("application/json")
                .body("{\"search\":\"" + search + "\"}")
                .post("/wp-json/wp/v2/posts?per_page=100&_fields=id");
        answer.then().statusCode(200);
        assertThat(ids(answer)).contains(visible.value());
    }

    @Test
    void totalsCountOnlyReadablePosts() {
        String u = UUID.randomUUID().toString();
        List<Long> own = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            own.add(PostFixtures.builder(site)
                    .status("draft")
                    .author(dave)
                    .title("own " + i + " " + u)
                    .publishedAt(null)
                    .modifiedAt("2026-07-01T10:00:0" + i + "Z")
                    .insert(data)
                    .value());
        }
        for (int i = 0; i < 7; i++) {
            PostFixtures.builder(site)
                    .status("draft")
                    .author(carol)
                    .title("other " + i + " " + u)
                    .publishedAt(null)
                    .modifiedAt("2026-07-01T11:00:0" + i + "Z")
                    .insert(data);
        }
        Response answer =
                as("test-" + dave).get("/wp-json/wp/v2/posts?status=draft&context=edit&search=" + u + "&_fields=id");
        answer.then().statusCode(200).header("X-WP-Total", "3").header("X-WP-TotalPages", "1");
        assertThat(ids(answer)).containsExactlyInAnyOrderElementsOf(own);
        // the editor reads every draft
        Response every = as("test-" + editor).get("/wp-json/wp/v2/posts?status=draft&search=" + u + "&_fields=id");
        every.then().statusCode(200).header("X-WP-Total", "10");
    }

    @Test
    void fieldsInWordPressOrderPerType() {
        String u = UUID.randomUUID().toString();
        PostId post = PostFixtures.builder(site)
                .author(carol)
                .slug("fields-" + u.substring(0, 8))
                .title("Fields " + u)
                .content("<!-- wp:paragraph -->\n<p>One two three.</p>\n<!-- /wp:paragraph -->")
                .publishedAt("2026-08-01T10:00:00Z")
                .modifiedAt("2026-08-01T10:30:00Z")
                .insert(data);
        Response view = as(null).get("/wp-json/wp/v2/posts?include=" + post.value());
        view.then().statusCode(200);
        JsonNode item = json(view.asString()).get(0);
        assertThat(fieldNames(item))
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
                        "tags");
        assertThat(item.get("date").asText()).isEqualTo("2026-08-01T12:00:00");
        assertThat(item.get("date_gmt").asText()).isEqualTo("2026-08-01T10:00:00");
        assertThat(item.get("modified").asText()).isEqualTo("2026-08-01T12:30:00");
        assertThat(item.get("modified_gmt").asText()).isEqualTo("2026-08-01T10:30:00");
        assertThat(fieldNames(item.get("guid"))).containsExactly("rendered");
        assertThat(item.get("link").asText()).isEqualTo("http://" + host + "/?p=" + post.value());
        assertThat(fieldNames(item.get("title"))).containsExactly("rendered");
        assertThat(item.get("title").get("rendered").asText()).isEqualTo("Fields " + u);
        assertThat(fieldNames(item.get("content"))).containsExactly("rendered", "protected");
        assertThat(item.get("content").get("protected").asBoolean()).isFalse();
        assertThat(fieldNames(item.get("excerpt"))).containsExactly("rendered", "protected");
        assertThat(item.get("excerpt").get("rendered").asText()).isEqualTo("One two three.");
        assertThat(item.get("author").asLong()).isEqualTo(carol);
        assertThat(item.get("featured_media").asLong()).isZero();
        assertThat(item.get("sticky").asBoolean()).isFalse();
        assertThat(item.get("template").asText()).isEmpty();
        assertThat(item.get("format").asText()).isEqualTo("standard");
        assertThat(item.get("meta").toString()).isEqualTo("{\"footnotes\":\"\"}");
        assertThat(item.get("categories").toString()).isEqualTo("[]");
        assertThat(item.get("tags").toString()).isEqualTo("[]");
        // the edit context adds the raw fields, the password and the block version, in WordPress's places
        JsonNode edit = json(as("test-" + administrator)
                        .get("/wp-json/wp/v2/posts?include=" + post.value() + "&context=edit")
                        .asString())
                .get(0);
        assertThat(fieldNames(edit))
                .startsWith(
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
                        "content");
        assertThat(fieldNames(edit.get("guid"))).containsExactly("rendered", "raw");
        assertThat(fieldNames(edit.get("title"))).containsExactly("raw", "rendered");
        assertThat(fieldNames(edit.get("content"))).containsExactly("raw", "rendered", "protected", "block_version");
        assertThat(edit.get("content").get("block_version").asInt()).isEqualTo(1);
        assertThat(fieldNames(edit.get("excerpt"))).containsExactly("raw", "rendered", "protected");
        assertThat(edit.get("password").asText()).isEmpty();
        // a page: parent and menu_order, no sticky, format or taxonomies
        PostId page = PostFixtures.builder(site)
                .type("page")
                .author(editor)
                .title("Page " + u)
                .publishedAt("2026-08-01T10:00:00Z")
                .insert(data);
        JsonNode pageItem = json(as(null).get("/wp-json/wp/v2/pages?include=" + page.value())
                        .asString())
                .get(0);
        assertThat(fieldNames(pageItem))
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
                        "meta");
        assertThat(pageItem.get("link").asText()).isEqualTo("http://" + host + "/?page_id=" + page.value());
        assertThat(pageItem.get("parent").asLong()).isZero();
        // a book: no sticky, format, parent, menu_order or comment status; a published book links by its slug
        PostId book = PostFixtures.builder(site)
                .type("book")
                .author(editor)
                .slug("book-" + u.substring(0, 8))
                .title("Book " + u)
                .publishedAt("2026-08-01T10:00:00Z")
                .insert(data);
        JsonNode bookItem = json(as(null).get("/wp-json/wp/v2/books?include=" + book.value())
                        .asString())
                .get(0);
        assertThat(fieldNames(bookItem))
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
                        "template",
                        "meta");
        assertThat(bookItem.get("link").asText()).isEqualTo("http://" + host + "/?book=book-" + u.substring(0, 8));
        assertThat(bookItem.get("meta").toString()).isEqualTo("{\"footnotes\":\"\"}");
        // a draft book links by its id; a floating draft's date_gmt is its date converted from Europe/Paris
        PostId draftBook = PostFixtures.builder(site)
                .type("book")
                .status("draft")
                .author(editor)
                .title("Draft book " + u)
                .publishedAt(null)
                .modifiedAt("2026-07-15T10:00:00Z")
                .insert(data);
        JsonNode draft = json(as("test-" + editor)
                        .get("/wp-json/wp/v2/books?status=draft&include=" + draftBook.value())
                        .asString())
                .get(0);
        assertThat(draft.get("link").asText()).isEqualTo("http://" + host + "/?post_type=book&p=" + draftBook.value());
        assertThat(draft.get("date").asText()).isEqualTo("2026-07-15T12:00:00");
        assertThat(draft.get("date_gmt").asText()).isEqualTo("2026-07-15T10:00:00");
        assertThat(draft.get("modified_gmt").asText()).isEqualTo("2026-07-15T10:00:00");
        // the embed context
        JsonNode embed = json(as(null).get("/wp-json/wp/v2/posts?include=" + post.value() + "&context=embed")
                        .asString())
                .get(0);
        assertThat(fieldNames(embed))
                .containsExactly("id", "date", "slug", "type", "link", "title", "excerpt", "author", "featured_media");
        // a protected post renders nothing of its content or excerpt
        PostId locked = PostFixtures.builder(site)
                .author(carol)
                .title("Locked " + u)
                .content("Secret words")
                .password("s3")
                .publishedAt("2026-08-01T10:00:00Z")
                .insert(data);
        JsonNode lockedItem = json(as("test-" + administrator)
                        .get("/wp-json/wp/v2/posts?include=" + locked.value())
                        .asString())
                .get(0);
        assertThat(lockedItem.get("content").get("rendered").asText()).isEmpty();
        assertThat(lockedItem.get("content").get("protected").asBoolean()).isTrue();
        assertThat(lockedItem.get("excerpt").get("rendered").asText()).isEmpty();
        assertThat(lockedItem.get("excerpt").get("protected").asBoolean()).isTrue();
        // the generated excerpt is cut to excerpt_length words with WordPress's ellipsis
        PostId longPost = PostFixtures.builder(site)
                .author(carol)
                .title("Long " + u)
                .content("<p>one two</p>\n<p>three   four five</p>")
                .publishedAt("2026-08-01T10:00:00Z")
                .insert(data);
        JsonNode cut = json(as(null).get("/wp-json/wp/v2/posts?include=" + longPost.value() + "&excerpt_length=3")
                        .asString())
                .get(0);
        assertThat(cut.get("excerpt").get("rendered").asText()).isEqualTo("one two three [&hellip;]");
    }

    // ---- helpers

    private static PostId post(String status, String title, String iso) {
        return PostFixtures.builder(site)
                .status(status)
                .author(carol)
                .title(title)
                .publishedAt(iso)
                .modifiedAt(iso)
                .insert(data);
    }

    static List<Long> ids(Response response) {
        response.then().statusCode(200);
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : json(response.asString())) {
            ids.add(item.get("id").asLong());
        }
        return ids;
    }

    static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
