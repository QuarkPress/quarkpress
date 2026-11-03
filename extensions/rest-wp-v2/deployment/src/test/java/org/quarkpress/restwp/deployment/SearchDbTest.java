package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

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
 * {@code GET /wp/v2/search}: the post handler (published posts, pages and books for every caller, newest first
 * without a search, protected posts hidden from anonymous searches), the term handler (categories and tags, the
 * taxonomy as the item's type), the post-format handler (the formats whose term exists, as strings), the
 * parameter rules of {@code context}, {@code type} and {@code subtype}, the subtype fallbacks, and paging past the
 * end, which answers an empty page with zero totals for posts and is refused for terms and formats. The class's site
 * has the administrator alice as its only member; counts that must be exact run on a site of their own.
 */
class SearchDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            RestApp.network(SearchDbTest.class, BookType.class, PostFixtures.class, PostFixtures.Builder.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static String home;
    static String root;
    static long alice;
    static long uncategorized;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.site(data);
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        home = "http://" + host + "/";
        root = home + "wp-json/";
        UserId admin = TestData.profile(data, "administrator");
        TestData.member(data, site, admin, "[\"administrator\"]");
        alice = admin.value();
        Jdbc.update(
                data,
                "insert into qp_term (site_id, taxonomy, name, slug, description) values (?, 'category',"
                        + " 'Uncategorized', 'uncategorized', '')",
                site.value());
        uncategorized = TestData.termId(data, site, "category", "uncategorized");
    }

    static RequestSpecification as(String bearer) {
        return Http.at(host, bearer);
    }

    static RequestSpecification alice() {
        return as("test-" + alice);
    }

    @Test
    void postResultsArePublishedOnlyForEveryone() {
        // a site of its own, so an empty search lists exactly this test's posts
        SiteId own = TestData.site(data);
        TestData.member(data, own, UserId.of(alice), "[\"administrator\"]");
        String ownHost = Jdbc.strings(data, "select domain from qp_site where id = ?", own.value())
                .get(0);
        String ownRoot = "http://" + ownHost + "/wp-json/";
        String u = "s1" + UUID.randomUUID().toString().replace("-", "");
        List<Long> mine = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            mine.add(post(own, "publish", u + " own " + i, "2026-03-0" + (i + 1) + "T10:00:00Z")
                    .value());
        }
        PostId ownDraft = post(own, "draft", u + " own draft", "2026-03-09T10:00:00Z");
        // a private post is not published either: alice may read it, the search never lists it
        PostId ownPrivate = post(own, "private", u + " own private", "2026-03-10T10:00:00Z");
        Response first = Http.at(ownHost, "test-" + alice).get("/wp-json/wp/v2/search?search=&per_page=3&type=post");
        first.then()
                .statusCode(200)
                .header("Allow", "GET")
                .header("X-WP-Total", "5")
                .header("X-WP-TotalPages", "2")
                .header("Link", "<" + ownRoot + "wp/v2/search?search&per_page=3&type=post&page=2>; rel=\"next\"");
        // newest first without a search; the draft never appears, alice being the one who could read it
        assertThat(ids(first)).containsExactly(mine.get(4), mine.get(3), mine.get(2));
        Response second =
                Http.at(ownHost, "test-" + alice).get("/wp-json/wp/v2/search?search=&per_page=3&type=post&page=2");
        second.then()
                .statusCode(200)
                .header("X-WP-Total", "5")
                .header("X-WP-TotalPages", "2")
                .header("Link", "<" + ownRoot + "wp/v2/search?search&per_page=3&type=post&page=1>; rel=\"prev\"");
        assertThat(ids(second))
                .containsExactly(mine.get(1), mine.get(0))
                .doesNotContain(ownDraft.value(), ownPrivate.value());
        assertThat(ids(Http.at(ownHost, "test-" + alice)
                        .queryParam("search", u)
                        .queryParam("per_page", 20)
                        .get("/wp-json/wp/v2/search")))
                .containsExactlyInAnyOrderElementsOf(mine);
        // the shared site: the same rules
        List<Long> published = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            published.add(post("publish", u + " post " + (i == 4 ? "newest" : i), "2026-03-0" + (i + 1) + "T10:00:00Z")
                    .value());
        }
        PostId draft = post("draft", u + " draft", "2026-03-09T10:00:00Z");
        assertThat(ids(as(null).get("/wp-json/wp/v2/search?search=" + u + "&per_page=10")))
                .containsExactlyInAnyOrderElementsOf(published)
                .doesNotContain(draft.value());
        // the item: id, title, url (the plain link), type post, subtype the post type; self (embeddable, with
        // target hints), about (the type) and collection (the search route)
        long newest = published.get(4);
        as(null).queryParam("search", u + " newest")
                .get("/wp-json/wp/v2/search")
                .then()
                .statusCode(200)
                .header("X-WP-Total", "1")
                .body("[0].id", equalTo((int) newest))
                .body("[0].title", equalTo(u + " post newest"))
                .body("[0].url", equalTo(home + "?p=" + newest))
                .body("[0].type", equalTo("post"))
                .body("[0].subtype", equalTo("post"))
                .body("[0]._links.self[0].href", equalTo(root + "wp/v2/posts/" + newest))
                .body("[0]._links.self[0].embeddable", equalTo(true))
                .body("[0]._links.self[0].targetHints.allow", contains("GET"))
                .body("[0]._links.about[0].href", equalTo(root + "wp/v2/types/post"))
                .body("[0]._links.collection[0].href", equalTo(root + "wp/v2/search"))
                .body("[0]", not(hasKey("_embedded")));
        assertThat(fieldNames(json(as(null).queryParam("search", u + " newest")
                                .get("/wp-json/wp/v2/search")
                                .asString())
                        .get(0)))
                .containsExactly("id", "title", "url", "type", "subtype", "_links");
        // pages and books list with their own plain links and routes; include wins over exclude
        PostId page = PostFixtures.builder(site)
                .type("page")
                .title(u + " page")
                .publishedAt("2026-03-10T10:00:00Z")
                .insert(data);
        PostId book = PostFixtures.builder(site)
                .type("book")
                .slug(u + "-book")
                .title(u + " book")
                .publishedAt("2026-03-11T10:00:00Z")
                .insert(data);
        as(null).get("/wp-json/wp/v2/search?search=" + u + "&include=" + page.value() + "," + book.value() + "&exclude="
                        + book.value())
                .then()
                .statusCode(200)
                .header("X-WP-Total", "2")
                .body("[0].id", equalTo((int) book.value()))
                .body("[0].url", equalTo(home + "?book=" + u + "-book"))
                .body("[0].subtype", equalTo("book"))
                .body("[0]._links.self[0].href", equalTo(root + "wp/v2/books/" + book.value()))
                .body("[0]._links.about[0].href", equalTo(root + "wp/v2/types/book"))
                .body("[1].id", equalTo((int) page.value()))
                .body("[1].url", equalTo(home + "?page_id=" + page.value()))
                .body("[1].subtype", equalTo("page"))
                .body("[1]._links.self[0].href", equalTo(root + "wp/v2/pages/" + page.value()));
        // the embed context lists the same fields; _embed embeds the post
        as(null).queryParam("search", u + " book")
                .get("/wp-json/wp/v2/search?context=embed&_embed")
                .then()
                .statusCode(200)
                .body("[0].id", equalTo((int) book.value()))
                .body("[0]._embedded.self[0].id", equalTo((int) book.value()))
                .body("[0]._embedded.self[0].title.rendered", equalTo(u + " book"));
    }

    @Test
    void termResultsCarryTheTaxonomyAsType() {
        as(null).get("/wp-json/wp/v2/search?type=term&search=Uncat")
                .then()
                .statusCode(200)
                .header("X-WP-Total", "1")
                .body("[0].id", equalTo((int) uncategorized))
                .body("[0].title", equalTo("Uncategorized"))
                .body("[0].url", equalTo(home + "?cat=" + uncategorized))
                .body("[0].type", equalTo("category"))
                .body("[0]", not(hasKey("subtype")))
                .body("[0]._links.self[0].href", equalTo(root + "wp/v2/categories/" + uncategorized))
                .body("[0]._links.self[0].embeddable", equalTo(true))
                .body("[0]._links.about[0].href", equalTo(root + "wp/v2/taxonomies/category"))
                .body("[0]._links.collection[0].href", equalTo(root + "wp/v2/search"));
        // tags too, by name ascending, with the tag's plain link
        String u = "t" + UUID.randomUUID().toString().replace("-", "");
        Jdbc.update(
                data,
                "insert into qp_term (site_id, taxonomy, name, slug, description) values (?, 'post_tag', ?, ?, '')",
                site.value(),
                u + " zeta",
                u + "-zeta");
        Jdbc.update(
                data,
                "insert into qp_term (site_id, taxonomy, name, slug, description) values (?, 'post_tag', ?, ?, '')",
                site.value(),
                u + " alpha",
                u + "-alpha");
        long zeta = TestData.termId(data, site, "post_tag", u + "-zeta");
        long alpha = TestData.termId(data, site, "post_tag", u + "-alpha");
        as(null).get("/wp-json/wp/v2/search?type=term&search=" + u)
                .then()
                .statusCode(200)
                .header("X-WP-Total", "2")
                .body("[0].id", equalTo((int) alpha))
                .body("[0].url", equalTo(home + "?tag=" + u + "-alpha"))
                .body("[0].type", equalTo("post_tag"))
                .body("[0]._links.self[0].href", equalTo(root + "wp/v2/tags/" + alpha))
                .body("[1].id", equalTo((int) zeta));
        // a page past the end of the terms is refused: this handler counts without paging
        as(null).get("/wp-json/wp/v2/search?type=term&search=" + u + "&per_page=1&page=3")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_search_invalid_page_number"))
                .body("message", equalTo("The page number requested is larger than the number of pages available."));
        // subtype narrows the taxonomies; include and exclude filter by term id
        as(null).get("/wp-json/wp/v2/search?type=term&search=" + u + "&subtype=category")
                .then()
                .statusCode(200)
                .header("X-WP-Total", "0")
                .body("size()", equalTo(0));
        as(null).get("/wp-json/wp/v2/search?type=term&search=" + u + "&exclude=" + alpha)
                .then()
                .statusCode(200)
                .header("X-WP-Total", "1")
                .body("[0].id", equalTo((int) zeta));
    }

    @Test
    void postFormatsAreListedOnceTheirTermExists() {
        // a fresh site has no format term: nothing is listed
        as(null).get("/wp-json/wp/v2/search?type=post-format")
                .then()
                .statusCode(200)
                .header("X-WP-Total", "0")
                .header("X-WP-TotalPages", "0")
                .body("size()", equalTo(0));
        PostId aside = post("publish", "an aside", "2026-04-01T10:00:00Z");
        Jdbc.update(
                data,
                "insert into qp_term (site_id, taxonomy, name, slug, description) values (?, 'post_format',"
                        + " 'post-format-aside', 'post-format-aside', '')",
                site.value());
        Jdbc.update(
                data,
                "insert into qp_term_assignment (site_id, post_id, term_id) values (?, ?, ?)",
                site.value(),
                aside.value(),
                TestData.termId(data, site, "post_format", "post-format-aside"));
        String expected = "[{\"id\":\"aside\",\"title\":\"Aside\",\"url\":\"" + home
                + "?post_format=aside\",\"type\":\"post-format\",\"_links\":{\"collection\":[{\"href\":\"" + root
                + "wp/v2/search\"}]}}]";
        // WordPress's JSON escapes slashes
        expected = expected.replace("/", "\\/");
        Response listed = as(null).get("/wp-json/wp/v2/search?type=post-format");
        listed.then().statusCode(200).header("X-WP-Total", "1").header("X-WP-TotalPages", "1");
        assertThat(listed.asString()).isEqualTo(expected);
        assertThat(as(null).get("/wp-json/wp/v2/search?search=aside&type=post-format")
                        .asString())
                .isEqualTo(expected);
        // the label matches case-insensitively too
        assertThat(as(null).get("/wp-json/wp/v2/search?search=ASI&type=post-format")
                        .asString())
                .isEqualTo(expected);
        as(null).get("/wp-json/wp/v2/search?search=gallery&type=post-format")
                .then()
                .statusCode(200)
                .header("X-WP-Total", "0")
                .body("size()", equalTo(0));
        // a page past the end of the formats is refused: this handler counts without paging
        as(null).get("/wp-json/wp/v2/search?type=post-format&per_page=1&page=2")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_search_invalid_page_number"))
                .body("message", equalTo("The page number requested is larger than the number of pages available."));
        // subtype and include are ignored by this handler
        assertThat(as(null).get("/wp-json/wp/v2/search?type=post-format&subtype=page&include=1")
                        .asString())
                .isEqualTo(expected);
    }

    @Test
    void parameterRules() {
        as(null).get("/wp-json/wp/v2/search?context=edit")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("message", equalTo("Invalid parameter(s): context"))
                .body("data.params.context", equalTo("context is not one of view and embed."))
                .body("data.details.context.code", equalTo("rest_not_in_enum"));
        as(null).get("/wp-json/wp/v2/search?type=bogus")
                .then()
                .statusCode(400)
                .body("data.params.type", equalTo("type is not one of post, term, and post-format."))
                .body("data.details.type.code", equalTo("rest_not_in_enum"));
        as(null).get("/wp-json/wp/v2/search?subtype=bogus")
                .then()
                .statusCode(400)
                .body(
                        "data.params.subtype",
                        equalTo("subtype[0] is not one of post, page, book, category, post_tag, and any."))
                .body("data.details.subtype.code", equalTo("rest_not_in_enum"));
        String u = "s4" + UUID.randomUUID().toString().replace("-", "");
        List<Long> posts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            posts.add(post("publish", u + " x " + i, "2026-05-0" + (i + 1) + "T10:00:00Z")
                    .value());
        }
        PostId page = PostFixtures.builder(site)
                .type("page")
                .title(u + " x page")
                .publishedAt("2026-05-10T10:00:00Z")
                .insert(data);
        PostId book = PostFixtures.builder(site)
                .type("book")
                .slug(u + "-x-book")
                .title(u + " x book")
                .publishedAt("2026-05-11T10:00:00Z")
                .insert(data);
        // a page past the end is an empty page with zero totals and an unclamped prev link, never 400
        // (the sanitized subtype list is what the links carry, as WordPress writes its sanitized arguments back)
        as(null).get("/wp-json/wp/v2/search?search=" + u + "&per_page=3&type=post&subtype=post&page=3")
                .then()
                .statusCode(200)
                .header("X-WP-Total", "0")
                .header("X-WP-TotalPages", "0")
                .header(
                        "Link",
                        "<" + root + "wp/v2/search?search=" + u + "&per_page=3&type=post&subtype%5B0%5D=post&page=2>;"
                                + " rel=\"prev\"")
                .body("size()", equalTo(0));
        as(null).get("/wp-json/wp/v2/search?search=" + u + "nothing&page=2")
                .then()
                .statusCode(200)
                .header("X-WP-Total", "0")
                .header("X-WP-TotalPages", "0")
                .header("Link", "<" + root + "wp/v2/search?search=" + u + "nothing&page=1>; rel=\"prev\"")
                .body("size()", equalTo(0));
        as(null).get("/wp-json/wp/v2/search?search=" + u + "&per_page=1&page=2")
                .then()
                .statusCode(200)
                .header("X-WP-Total", "7")
                .header("X-WP-TotalPages", "7")
                .header(
                        "Link",
                        "<" + root + "wp/v2/search?search=" + u + "&per_page=1&page=1>; rel=\"prev\", <" + root
                                + "wp/v2/search?search=" + u + "&per_page=1&page=3>; rel=\"next\"");
        // a subtype of another handler: the post handler falls back to post only
        assertThat(ids(as(null).get("/wp-json/wp/v2/search?type=post&subtype=category&search=" + u + "&per_page=20")))
                .containsExactlyInAnyOrderElementsOf(posts);
        // a subtype of another handler for terms: every REST taxonomy, as without subtype
        Jdbc.update(
                data,
                "insert into qp_term (site_id, taxonomy, name, slug, description) values (?, 'post_tag', ?, ?, '')",
                site.value(),
                u + " tag",
                u + "-tag");
        long tag = TestData.termId(data, site, "post_tag", u + "-tag");
        as(null).get("/wp-json/wp/v2/search?type=term&subtype=page&search=" + u)
                .then()
                .statusCode(200)
                .header("X-WP-Total", "1")
                .body("[0].id", equalTo((int) tag))
                .body("[0].type", equalTo("post_tag"));
        as(null).get("/wp-json/wp/v2/search?type=term&search=" + u)
                .then()
                .statusCode(200)
                .header("X-WP-Total", "1")
                .body("[0].id", equalTo((int) tag));
        // subtypes of the handler narrow it; any anywhere means every subtype
        assertThat(ids(as(null).get("/wp-json/wp/v2/search?type=post&subtype=page,book&search=" + u)))
                .containsExactly(book.value(), page.value());
        assertThat(ids(as(null).get("/wp-json/wp/v2/search?type=post&subtype=page,any&search=" + u + "&per_page=20")))
                .hasSize(7);
        // an empty subtype names none of the handler's: posts alone, as for another handler's subtype
        assertThat(ids(as(null).get("/wp-json/wp/v2/search?type=post&subtype=&search=" + u + "&per_page=20")))
                .containsExactlyInAnyOrderElementsOf(posts);
        // a protected post's word: hidden from an anonymous search, found by a caller who may edit it
        PostId locked = PostFixtures.builder(site)
                .title(u + " locked")
                .content(u + "secretword")
                .password("pw")
                .publishedAt("2026-05-12T10:00:00Z")
                .insert(data);
        as(null).get("/wp-json/wp/v2/search?search=" + u + "secretword")
                .then()
                .statusCode(200)
                .header("X-WP-Total", "0")
                .body("size()", equalTo(0));
        alice().get("/wp-json/wp/v2/search?search=" + u + "secretword")
                .then()
                .statusCode(200)
                .header("X-WP-Total", "1")
                .body("[0].id", equalTo((int) locked.value()))
                .body("[0].title", equalTo(u + " locked"));
        // HEAD answers the totals without a body
        Response head = as(null).head("/wp-json/wp/v2/search?search=" + u + "&per_page=2");
        head.then()
                .statusCode(200)
                .header("Allow", "GET")
                .header("X-WP-Total", "7")
                .header("X-WP-TotalPages", "4");
        assertThat(head.asString()).isEmpty();
    }

    private static PostId post(String status, String title, String iso) {
        return post(site, status, title, iso);
    }

    private static PostId post(SiteId on, String status, String title, String iso) {
        return PostFixtures.builder(on)
                .status(status)
                .author(alice)
                .title(title)
                .publishedAt(status.equals("draft") ? null : iso)
                .modifiedAt(iso)
                .insert(data);
    }

    private static List<Long> ids(Response response) {
        response.then().statusCode(200);
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : json(response.asString())) {
            ids.add(item.get("id").asLong());
        }
        return ids;
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
