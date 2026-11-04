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
 * The revisions routes ({@code /wp/v2/posts/<parent>/revisions} and {@code .../revisions/<id>}, pages and books
 * alike): the lookups and their 404s, the read permission, WordPress's paging quirks, the orderings, the search, the
 * fields per type and context, the refused DELETE, another site's rows and the site whose revisions are off. The
 * site's zone is Europe/Paris; members: an administrator, the editor bob, the author carol and the contributor dave.
 * The fixture post is bob's post 7 with the revisions r8 (10:00) and r9 (11:00) and dave's autosave r10 (12:00).
 */
class RevisionsRoutesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
            RevisionsRoutesDbTest.class, BookType.class, PostFixtures.class, PostFixtures.Builder.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long administrator;
    static long bob;
    static long carol;
    static long dave;
    static PostId post7;
    static long r8;
    static long r9;
    static long r10;
    static PostId post12;
    static long r15;

    @BeforeAll
    static void fixtures() {
        data = Jdbc.dataSource();
        site = TestData.siteIn(data, "Europe/Paris");
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        administrator = member(site, "administrator");
        bob = member(site, "editor");
        carol = member(site, "author");
        dave = member(site, "contributor");
        post7 = post(site, "post", bob, "post seven").insert(data);
        r8 = revision(site, post7, bob, "seven v1", "first words", "2026-03-01T10:00:00Z");
        r9 = revision(site, post7, bob, "seven v2", "second words", "2026-03-01T11:00:00Z");
        r10 = autosave(site, post7, dave, "seven autosave", "dave's words", "2026-03-01T12:00:00Z");
        post12 = post(site, "post", bob, "post twelve").insert(data);
        r15 = revision(site, post12, bob, "twelve v1", "twelve words", "2026-03-02T10:00:00Z");
    }

    private static long member(SiteId where, String role) {
        UserId user = TestData.profile(data, role);
        TestData.member(data, where, user, "[\"" + role + "\"]");
        return user.value();
    }

    /** A request to the test site as the user with {@code bearer}, or anonymous when null. */
    static RequestSpecification as(String bearer) {
        return Http.at(host, bearer);
    }

    private static String admin() {
        return "test-" + administrator;
    }

    private static PostFixtures.Builder post(SiteId where, String type, long author, String title) {
        return PostFixtures.builder(where)
                .type(type)
                .author(author)
                .title(title)
                .content("the post's words")
                .publishedAt("2026-03-01T09:00:00Z")
                .modifiedAt("2026-03-01T12:00:00Z");
    }

    /** A regular revision row of {@code author} on the post, written past the services; answers its id. */
    private static long revision(
            SiteId where, PostId post, long author, String title, String content, String createdAt) {
        return row(where, post, author, title, content, false, createdAt);
    }

    /** An autosave row of {@code author} on the post, written past the services; answers its id. */
    private static long autosave(
            SiteId where, PostId post, long author, String title, String content, String createdAt) {
        return row(where, post, author, title, content, true, createdAt);
    }

    private static long row(
            SiteId where, PostId post, long author, String title, String content, boolean autosave, String createdAt) {
        return row(where, post, author, title, content, autosave, createdAt, "{}");
    }

    private static long row(
            SiteId where,
            PostId post,
            long author,
            String title,
            String content,
            boolean autosave,
            String createdAt,
            String meta) {
        String marker = "rev-" + UUID.randomUUID();
        Jdbc.update(
                data,
                "insert into qp_revision (site_id, post_id, author_id, title, content, excerpt, meta, autosave,"
                        + " autosave_owner, created_at) values (?, ?, ?, ?, ?, ?, " + TestDb.json() + ", ?, ?, "
                        + PostFixtures.instant() + ")",
                where.value(),
                post.value(),
                author,
                title,
                content,
                marker,
                meta,
                autosave,
                autosave ? author : null,
                PostFixtures.instantText(createdAt));
        long id = Jdbc.count(data, "select id from qp_revision where excerpt = ?", marker);
        Jdbc.update(data, "update qp_revision set excerpt = '' where id = ?", id);
        return id;
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

    private static List<Long> ids(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        list.forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    private static String list(PostId parent) {
        return "/wp-json/wp/v2/posts/" + parent.value() + "/revisions";
    }

    private static String error(String code, String message, int status) {
        return "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":{\"status\":" + status + "}}";
    }

    @Test
    void lookupsAre404() {
        as(admin())
                .get("/wp-json/wp/v2/posts/999999/revisions")
                .then()
                .statusCode(404)
                .header("Allow", nullValue())
                .body(equalTo(error("rest_post_invalid_parent", "Invalid post parent ID.", 404)));
        PostId aPage = post(site, "page", bob, "a page").insert(data);
        as(admin())
                .get(list(aPage))
                .then()
                .statusCode(404)
                .body(equalTo(error("rest_post_invalid_parent", "Invalid post parent ID.", 404)));
        as(admin())
                .get("/wp-json/wp/v2/posts/0/revisions")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_parent"));
        // revision ids live in their own space (README D31), so a missing id stands in for WordPress's post id
        as(admin())
                .get(list(post7) + "/999999")
                .then()
                .statusCode(404)
                .header("Allow", equalTo("GET"))
                .body(equalTo(error("rest_post_invalid_id", "Invalid revision ID.", 404)));
        as(admin())
                .get(list(post7) + "/" + r15)
                .then()
                .statusCode(404)
                .header("Allow", equalTo("GET"))
                .body(equalTo(error(
                        "rest_revision_parent_id_mismatch",
                        "The revision does not belong to the specified parent with id of \\\"" + post7.value() + "\\\"",
                        404)));
        as(null).get("/wp-json/wp/v2/posts/999999/revisions")
                .then()
                .statusCode(404)
                .header("Allow", nullValue())
                .body("code", equalTo("rest_post_invalid_parent"));
        as(null).get("/wp-json/wp/v2/posts/abc/revisions")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_no_route"));
        as(admin()).post(list(post7)).then().statusCode(404).body("code", equalTo("rest_no_route"));
    }

    @Test
    void readNeedsEditPost() {
        as("test-" + dave)
                .get(list(post7))
                .then()
                .statusCode(403)
                .header("Allow", nullValue())
                .body(equalTo(
                        error("rest_cannot_read", "Sorry, you are not allowed to view revisions of this post.", 403)));
        as("test-" + dave).get(list(post7) + "/" + r8).then().statusCode(403).body("code", equalTo("rest_cannot_read"));
        as(null).get(list(post7))
                .then()
                .statusCode(401)
                .header("Allow", nullValue())
                .body(equalTo(
                        error("rest_cannot_read", "Sorry, you are not allowed to view revisions of this post.", 401)));
        PostId carolsDraft = post(site, "post", carol, "carol's draft")
                .status("draft")
                .publishedAt(null)
                .insert(data);
        long carolsRevision = revision(site, carolsDraft, carol, "draft v1", "draft words", "2026-03-03T10:00:00Z");
        Response carols = as("test-" + carol).get(list(carolsDraft));
        carols.then().statusCode(200).header("Allow", equalTo("GET"));
        assertThat(ids(json(carols.asString()))).containsExactly(carolsRevision);
        as("test-" + carol)
                .get(list(carolsDraft) + "/" + carolsRevision + "?context=edit")
                .then()
                .statusCode(200)
                .header("Allow", equalTo("GET"));
        // validation runs before the permission check
        as(null).get(list(post7) + "?per_page=0")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body("data.details.per_page.code", equalTo("rest_out_of_bounds"))
                .body(
                        "data.details.per_page.message",
                        equalTo("per_page must be between 1 (inclusive) and 100 (inclusive)"));
    }

    @Test
    void aProtectedParentIsNotSpecial() {
        PostId locked = post(site, "post", carol, "locked parent")
                .password("pw")
                .content("LOCKED POST WORDS")
                .insert(data);
        long revision = revision(site, locked, carol, "locked v1", "LOCKED REVISION WORDS", "2026-03-02T10:00:00Z");
        long autosave = autosave(site, locked, carol, "locked draft", "LOCKED AUTOSAVE WORDS", "2026-03-03T10:00:00Z");
        String autosaves = "/wp-json/wp/v2/posts/" + locked.value() + "/autosaves";
        // whoever may edit the parent reads the words, in every context, with no password and no protected flag
        for (String context : new String[] {"view", "edit"}) {
            JsonNode revisions = json(as("test-" + carol)
                    .get(list(locked) + "?context=" + context)
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString());
            assertThat(ids(revisions)).contains(revision);
            JsonNode listed = revisions.get(ids(revisions).indexOf(revision));
            assertThat(listed.at("/content/rendered").textValue()).contains("LOCKED REVISION WORDS");
            assertThat(fieldNames(listed.get("content"))).doesNotContain("protected");
            JsonNode item = json(as("test-" + bob)
                    .get(list(locked) + "/" + revision + "?context=" + context)
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString());
            assertThat(item.at("/content/rendered").textValue()).contains("LOCKED REVISION WORDS");
            JsonNode saved = json(as("test-" + carol)
                    .get(autosaves + "?context=" + context)
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString());
            assertThat(ids(saved)).containsExactly(autosave);
            assertThat(saved.at("/0/content/rendered").textValue()).contains("LOCKED AUTOSAVE WORDS");
            assertThat(fieldNames(saved.get(0).get("content"))).doesNotContain("protected");
            JsonNode one = json(as("test-" + bob)
                    .get(autosaves + "/" + autosave + "?context=" + context)
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString());
            assertThat(one.at("/content/rendered").textValue()).contains("LOCKED AUTOSAVE WORDS");
        }
        // a password never opens them to anyone else
        for (String target : new String[] {
            list(locked) + "?password=pw",
            list(locked) + "/" + revision + "?password=pw",
            autosaves + "?password=pw",
            autosaves + "/" + autosave + "?password=pw"
        }) {
            as(null).get(target).then().statusCode(401).body("code", equalTo("rest_cannot_read"));
            as("test-" + dave).get(target).then().statusCode(403).body("code", equalTo("rest_cannot_read"));
        }
    }

    @Test
    void listsAllUsersAutosavesAndPagesLikeWordPress() {
        String base = "http://" + host + list(post7);
        Response all = as(admin()).get(list(post7));
        all.then()
                .statusCode(200)
                .header("X-WP-Total", equalTo("3"))
                .header("X-WP-TotalPages", equalTo("1"))
                .header("Link", equalTo("<http://" + host + "/wp-json/>; rel=\"https://api.w.org/\""))
                .header("Allow", equalTo("GET"));
        assertThat(ids(json(all.asString()))).containsExactly(r10, r9, r8);
        Response ascending = as(admin()).get(list(post7) + "?order=asc");
        assertThat(ids(json(ascending.asString()))).containsExactly(r8, r9, r10);
        Response two = as(admin()).get(list(post7) + "?per_page=2");
        two.then()
                .statusCode(200)
                .header("X-WP-Total", equalTo("3"))
                .header("X-WP-TotalPages", equalTo("2"))
                .header("Link", equalTo("<" + base + "?per_page=2&page=2>; rel=\"next\""));
        assertThat(ids(json(two.asString()))).containsExactly(r10, r9);
        Response second = as(admin()).get(list(post7) + "?per_page=2&page=2");
        second.then().statusCode(200).header("Link", equalTo("<" + base + "?per_page=2&page=1>; rel=\"prev\""));
        assertThat(ids(json(second.asString()))).containsExactly(r8);
        as(admin())
                .get(list(post7) + "?per_page=2&page=3")
                .then()
                .statusCode(400)
                .header("Allow", equalTo("GET"))
                .body(equalTo(error(
                        "rest_revision_invalid_page_number",
                        "The page number requested is larger than the number of pages available.",
                        400)));
        as(admin())
                .get(list(post7) + "?offset=3")
                .then()
                .statusCode(400)
                .header("Allow", equalTo("GET"))
                .body(equalTo(error(
                        "rest_revision_invalid_offset_number",
                        "The offset number requested is larger than or equal to the number of available revisions.",
                        400)));
        // without per_page the offset is checked but does not move the window
        Response offsetOne = as(admin()).get(list(post7) + "?offset=1");
        offsetOne.then().statusCode(200).header("X-WP-Total", equalTo("3")).header("X-WP-TotalPages", equalTo("1"));
        assertThat(ids(json(offsetOne.asString()))).containsExactly(r10, r9, r8);
        as(admin())
                .get(list(post7) + "?page=2")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_revision_invalid_page_number"));
        // an offset of 0 is given: the window starts at 0 whatever the page, which only moves the links
        Response pageTwoOffsetZero = as(admin()).get(list(post7) + "?per_page=1&page=2&offset=0");
        pageTwoOffsetZero
                .then()
                .statusCode(200)
                .header("X-WP-Total", equalTo("3"))
                .header("X-WP-TotalPages", equalTo("3"))
                .header(
                        "Link",
                        equalTo("<" + base + "?per_page=1&page=1&offset=0>; rel=\"prev\", <" + base
                                + "?per_page=1&page=3&offset=0>; rel=\"next\""));
        assertThat(ids(json(pageTwoOffsetZero.asString()))).containsExactly(r10);
        // with an offset of 0 a page past the last is refused; with any other offset the page is not checked
        as(admin())
                .get(list(post7) + "?per_page=1&page=4&offset=0")
                .then()
                .statusCode(400)
                .body(equalTo("{\"code\":\"rest_revision_invalid_page_number\",\"message\":\"The page number"
                        + " requested is larger than the number of pages available.\",\"data\":{\"status\":400}}"));
        Response pageNineOffsetOne = as(admin()).get(list(post7) + "?per_page=1&page=9&offset=1");
        pageNineOffsetOne
                .then()
                .statusCode(200)
                .header("X-WP-Total", equalTo("3"))
                .header("X-WP-TotalPages", equalTo("3"));
        assertThat(ids(json(pageNineOffsetOne.asString()))).containsExactly(r9);
        // a negative offset counts as its absolute value
        Response negative = as(admin()).get(list(post7) + "?per_page=1&offset=-1");
        negative.then()
                .statusCode(200)
                .header("X-WP-TotalPages", equalTo("3"))
                .header("Link", equalTo("<" + base + "?per_page=1&offset=-1&page=2>; rel=\"next\""));
        assertThat(ids(json(negative.asString()))).containsExactly(r9);
        Response offsetTwo = as(admin()).get(list(post7) + "?per_page=2&offset=1");
        offsetTwo.then().statusCode(200).header("X-WP-TotalPages", equalTo("2"));
        assertThat(ids(json(offsetTwo.asString()))).containsExactly(r9, r8);
        // the orderings
        Response include =
                as(admin()).get(list(post7) + "?orderby=include&include=" + r9 + "," + r8 + "," + r10 + "&order=desc");
        include.then().statusCode(200).header("X-WP-Total", equalTo("3"));
        assertThat(ids(json(include.asString()))).containsExactly(r9, r8, r10);
        Response includeTwo =
                as(admin()).get(list(post7) + "?orderby=include&include=" + r9 + "," + r10 + "&order=asc");
        includeTwo.then().statusCode(200).header("X-WP-Total", equalTo("2"));
        assertThat(ids(json(includeTwo.asString()))).containsExactly(r9, r10);
        Response includeSlugs = as(admin()).get(list(post7) + "?orderby=include_slugs&order=desc");
        assertThat(ids(json(includeSlugs.asString()))).containsExactly(r8, r9, r10);
        Response bySlug = as(admin()).get(list(post7) + "?orderby=slug");
        assertThat(ids(json(bySlug.asString()))).containsExactly(r8, r9, r10);
        Response bySlugAscending = as(admin()).get(list(post7) + "?orderby=slug&order=asc");
        assertThat(ids(json(bySlugAscending.asString()))).containsExactly(r10, r8, r9);
        Response byId = as(admin()).get(list(post7) + "?orderby=id&order=asc");
        assertThat(ids(json(byId.asString()))).containsExactly(r8, r9, r10);
        Response excluded = as(admin()).get(list(post7) + "?exclude=" + r9);
        excluded.then().header("X-WP-Total", equalTo("2"));
        assertThat(ids(json(excluded.asString()))).containsExactly(r10, r8);
        as(admin())
                .get(list(post7) + "?orderby=include")
                .then()
                .statusCode(400)
                .body(equalTo(error(
                        "rest_orderby_include_missing_include",
                        "You need to define an include parameter to order by include.",
                        400)));
        as(admin())
                .get(list(post7) + "?orderby=relevance")
                .then()
                .statusCode(400)
                .body(equalTo(error(
                        "rest_no_search_term_defined",
                        "You need to define a search term to order by relevance.",
                        400)));
        as(admin())
                .get(list(post7) + "?orderby=relevance&search=")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_no_search_term_defined"));
        // HEAD answers the headers alone
        as(admin())
                .head(list(post7) + "?per_page=2")
                .then()
                .statusCode(200)
                .header("X-WP-Total", equalTo("3"))
                .header("X-WP-TotalPages", equalTo("2"));
        // the version-history count of the post agrees with the total
        JsonNode links = json(as(admin())
                .get("/wp-json/wp/v2/posts/" + post7.value() + "?context=edit")
                .asString());
        assertThat(links.at("/_links/version-history/0/count").asInt()).isEqualTo(3);
    }

    @Test
    void searchesLikeWordPress() {
        PostId post15 = post(site, "post", bob, "post fifteen").insert(data);
        long r16 = revision(site, post15, bob, "f04 one", "f04 banana", "2026-03-04T10:00:00Z");
        long r17 = revision(site, post15, bob, "f04 two", "f04 cherry banana", "2026-03-04T11:00:00Z");
        long r18 = revision(site, post15, bob, "f04 banana title", "f04 three", "2026-03-04T12:00:00Z");
        Response banana = as(admin()).queryParam("search", "banana").get(list(post15));
        banana.then().statusCode(200).header("X-WP-Total", equalTo("3"));
        assertThat(ids(json(banana.asString()))).containsExactly(r18, r17, r16);
        Response relevance = as(admin())
                .queryParam("search", "banana")
                .queryParam("orderby", "relevance")
                .get(list(post15));
        assertThat(ids(json(relevance.asString()))).containsExactly(r18, r17, r16);
        Response phrase = as(admin()).queryParam("search", "\"cherry banana\"").get(list(post15));
        phrase.then().statusCode(200).header("X-WP-Total", equalTo("1"));
        assertThat(ids(json(phrase.asString()))).containsExactly(r17);
        Response excluding = as(admin()).queryParam("search", "banana -cherry").get(list(post15));
        excluding.then().statusCode(200).header("X-WP-Total", equalTo("2"));
        assertThat(ids(json(excluding.asString()))).containsExactly(r18, r16);
        Response none = as(admin()).queryParam("search", "durian").get(list(post15));
        none.then()
                .statusCode(200)
                .header("X-WP-Total", equalTo("0"))
                .header("X-WP-TotalPages", equalTo("0"))
                .body(equalTo("[]"));
    }

    @Test
    void aPostWithoutRevisionsAnswersAnEmptyList() {
        PostId post19 = post(site, "post", bob, "post nineteen").insert(data);
        String base = "http://" + host + list(post19);
        Response empty = as(admin()).get(list(post19) + "?page=9");
        empty.then()
                .statusCode(200)
                .header("X-WP-Total", equalTo("0"))
                .header("X-WP-TotalPages", equalTo("0"))
                .header("Link", equalTo("<" + base + "?page=0>; rel=\"prev\""))
                .header("Allow", equalTo("GET"))
                .body(equalTo("[]"));
        as(admin())
                .get(list(post19) + "?offset=5")
                .then()
                .statusCode(200)
                .header("X-WP-Total", equalTo("0"))
                .body(equalTo("[]"));
        JsonNode links = json(as(admin())
                .get("/wp-json/wp/v2/posts/" + post19.value() + "?context=edit")
                .asString());
        assertThat(links.at("/_links/version-history/0/count").asInt()).isEqualTo(0);
    }

    @Test
    void fieldsPerTypeAndContext() {
        String postsRoute = "http://" + host + "/wp-json/wp/v2/posts/" + post7.value();
        JsonNode edit = json(as(admin())
                .get(list(post7) + "/" + r8 + "?context=edit")
                .then()
                .statusCode(200)
                .header("Allow", equalTo("GET"))
                .extract()
                .asString());
        assertThat(fieldNames(edit))
                .containsExactly(
                        "author",
                        "date",
                        "date_gmt",
                        "id",
                        "modified",
                        "modified_gmt",
                        "parent",
                        "slug",
                        "guid",
                        "title",
                        "content",
                        "excerpt",
                        "meta",
                        "_links");
        assertThat(edit.get("author").asLong()).isEqualTo(bob);
        assertThat(edit.get("date").asText()).isEqualTo("2026-03-01T11:00:00");
        assertThat(edit.get("date_gmt").asText()).isEqualTo("2026-03-01T10:00:00");
        assertThat(edit.get("id").asLong()).isEqualTo(r8);
        assertThat(edit.get("modified").asText()).isEqualTo("2026-03-01T11:00:00");
        assertThat(edit.get("modified_gmt").asText()).isEqualTo("2026-03-01T10:00:00");
        assertThat(edit.get("parent").asLong()).isEqualTo(post7.value());
        assertThat(edit.get("slug").asText()).isEqualTo(post7.value() + "-revision-v1");
        assertThat(edit.get("guid").toString())
                .isEqualTo("{\"rendered\":\"http://" + host + "/?p=" + r8 + "\",\"raw\":\"http://" + host + "/?p=" + r8
                        + "\"}");
        assertThat(edit.get("title").toString()).isEqualTo("{\"raw\":\"seven v1\",\"rendered\":\"seven v1\"}");
        assertThat(edit.get("content").toString()).isEqualTo("{\"raw\":\"first words\",\"rendered\":\"first words\"}");
        assertThat(edit.get("excerpt").toString()).isEqualTo("{\"raw\":\"\",\"rendered\":\"\"}");
        assertThat(edit.get("meta").toString()).isEqualTo("{\"footnotes\":\"\"}");
        assertThat(edit.get("_links").toString()).isEqualTo("{\"parent\":[{\"href\":\"" + postsRoute + "\"}]}");
        JsonNode view = json(as(admin()).get(list(post7) + "/" + r10).asString());
        assertThat(fieldNames(view))
                .containsExactly(
                        "author",
                        "date",
                        "date_gmt",
                        "id",
                        "modified",
                        "modified_gmt",
                        "parent",
                        "slug",
                        "guid",
                        "title",
                        "content",
                        "excerpt",
                        "meta",
                        "_links");
        assertThat(view.get("author").asLong()).isEqualTo(dave);
        assertThat(view.get("slug").asText()).isEqualTo(post7.value() + "-autosave-v1");
        assertThat(fieldNames(view.get("guid"))).containsExactly("rendered");
        assertThat(fieldNames(view.get("title"))).containsExactly("rendered");
        assertThat(fieldNames(view.get("content"))).containsExactly("rendered");
        assertThat(fieldNames(view.get("excerpt"))).containsExactly("rendered");
        assertThat(view.get("excerpt").toString()).isEqualTo("{\"rendered\":\"\"}");
        JsonNode embed =
                json(as(admin()).get(list(post7) + "/" + r8 + "?context=embed").asString());
        assertThat(fieldNames(embed))
                .containsExactly("author", "date", "id", "parent", "slug", "title", "excerpt", "_links");
        assertThat(fieldNames(embed.get("title"))).containsExactly("rendered");
        assertThat(fieldNames(embed.get("excerpt"))).containsExactly("rendered");
        JsonNode embedList =
                json(as(admin()).get(list(post7) + "?context=embed").asString());
        assertThat(fieldNames(embedList.get(0)))
                .containsExactly("author", "date", "id", "parent", "slug", "title", "excerpt", "_links");
        // the version-history link of the post embeds nothing
        JsonNode embedded = json(as(admin())
                .get("/wp-json/wp/v2/posts/" + post7.value() + "?_embed=version-history&context=edit")
                .asString());
        assertThat(embedded.has("_embedded")).isFalse();
        // a page revision carries excerpt too, with the same keys as a post revision
        PostId page = post(site, "page", bob, "a page").insert(data);
        long pageRevision = revision(site, page, bob, "page v1", "page words", "2026-03-05T10:00:00Z");
        JsonNode pageView = json(as(admin())
                .get("/wp-json/wp/v2/pages/" + page.value() + "/revisions/" + pageRevision)
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(fieldNames(pageView)).isEqualTo(fieldNames(view));
        assertThat(pageView.get("excerpt").toString()).isEqualTo("{\"rendered\":\"\"}");
        assertThat(pageView.get("meta").toString()).isEqualTo("{\"footnotes\":\"\"}");
        assertThat(pageView.at("/_links/parent/0/href").asText())
                .isEqualTo("http://" + host + "/wp-json/wp/v2/pages/" + page.value());
        JsonNode pageEdit = json(as(admin())
                .get("/wp-json/wp/v2/pages/" + page.value() + "/revisions/" + pageRevision + "?context=edit")
                .asString());
        assertThat(pageEdit.get("excerpt").toString()).isEqualTo("{\"raw\":\"\",\"rendered\":\"\"}");
        // a book revision likewise
        PostId book = post(site, "book", bob, "a book").insert(data);
        long bookRevision = revision(site, book, bob, "book v1", "book words", "2026-03-05T11:00:00Z");
        JsonNode bookView = json(as(admin())
                .get("/wp-json/wp/v2/books/" + book.value() + "/revisions/" + bookRevision)
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(fieldNames(bookView)).isEqualTo(fieldNames(view));
        assertThat(bookView.at("/_links/parent/0/href").asText())
                .isEqualTo("http://" + host + "/wp-json/wp/v2/books/" + book.value());
        // the schema of the route: "<type>-revision", the fields in order, excerpt on pages too
        JsonNode options = json(as(admin())
                .options("/wp-json/wp/v2/pages/" + page.value() + "/revisions")
                .asString());
        assertThat(options.at("/schema/title").asText()).isEqualTo("page-revision");
        assertThat(fieldNames(options.at("/schema/properties")))
                .containsExactly(
                        "author",
                        "date",
                        "date_gmt",
                        "guid",
                        "id",
                        "modified",
                        "modified_gmt",
                        "parent",
                        "slug",
                        "title",
                        "content",
                        "excerpt",
                        "meta");
        assertThat(fieldNames(options.at("/endpoints/0/args")))
                .containsExactly(
                        "parent",
                        "context",
                        "page",
                        "per_page",
                        "search",
                        "exclude",
                        "include",
                        "offset",
                        "order",
                        "orderby");
        assertThat(options.at("/endpoints/0/args/per_page").has("default")).isFalse();
        JsonNode item = json(as(admin())
                .options(list(post7) + "/" + r8)
                .then()
                .header("Allow", equalTo("GET"))
                .extract()
                .asString());
        assertThat(item.get("methods").toString()).isEqualTo("[\"GET\",\"DELETE\"]");
        assertThat(item.at("/endpoints/1/args/force").toString())
                .isEqualTo("{\"type\":\"boolean\",\"default\":false,\"description\":\"Required to be true, as"
                        + " revisions do not support trashing.\",\"required\":false}");
        // _fields on the list
        JsonNode fields = json(as(admin()).get(list(post7) + "?_fields=id,slug").asString());
        assertThat(fieldNames(fields.get(0))).containsExactly("id", "slug");
    }

    @Test
    void deleteIsAlwaysRefused() {
        as(admin())
                .delete(list(post7) + "/" + r8 + "?force=true")
                .then()
                .statusCode(403)
                .header("Allow", equalTo("GET"))
                .body(equalTo(error("rest_cannot_delete", "Sorry, you are not allowed to delete this revision.", 403)));
        as(admin()).delete(list(post7) + "/" + r8).then().statusCode(403).body("code", equalTo("rest_cannot_delete"));
        as("test-" + dave)
                .delete(list(post7) + "/" + r8)
                .then()
                .statusCode(403)
                .header("Allow", nullValue())
                .body(equalTo(error(
                        "rest_cannot_delete", "Sorry, you are not allowed to delete revisions of this post.", 403)));
        as(null).delete(list(post7) + "/" + r8)
                .then()
                .statusCode(401)
                .header("Allow", nullValue())
                .body(equalTo(error(
                        "rest_cannot_delete", "Sorry, you are not allowed to delete revisions of this post.", 401)));
        as(admin())
                .delete(list(post7) + "/999999")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_id"));
        // the mismatch is not checked on DELETE
        as(admin()).delete(list(post7) + "/" + r15).then().statusCode(403).body("code", equalTo("rest_cannot_delete"));
        assertThat(Jdbc.count(data, "select count(*) from qp_revision where id = ?", r8))
                .isEqualTo(1);
        as(admin()).get(list(post7) + "/" + r8).then().statusCode(200).header("Allow", equalTo("GET"));
    }

    @Test
    void revisionedMetaIsReadFromTheRevision() {
        PostId post = post(site, "post", bob, "post with notes").insert(data);
        Jdbc.update(
                data,
                "update qp_post set meta = " + TestDb.json() + " where id = ?",
                "{\"footnotes\":[\"the post's notes\"]}",
                post.value());
        long withNotes = row(
                site,
                post,
                bob,
                "notes v1",
                "notes words",
                false,
                "2026-03-04T10:00:00Z",
                "{\"footnotes\":[\"the revision's notes\"]}");
        long withoutNotes = revision(site, post, bob, "notes v2", "more words", "2026-03-04T11:00:00Z");
        String revisions = "/wp-json/wp/v2/posts/" + post.value() + "/revisions";
        assertThat(json(as(admin())
                                .get(revisions + "/" + withNotes + "?context=edit")
                                .asString())
                        .get("meta")
                        .toString())
                .isEqualTo("{\"footnotes\":\"the revision's notes\"}");
        // a revisioned key the revision does not hold reads as its default, not as the post's value
        assertThat(json(as(admin()).get(revisions + "/" + withoutNotes).asString())
                        .get("meta")
                        .toString())
                .isEqualTo("{\"footnotes\":\"\"}");
        assertThat(json(as(admin())
                                .get("/wp-json/wp/v2/posts/" + post.value() + "?context=edit")
                                .asString())
                        .get("meta")
                        .toString())
                .isEqualTo("{\"footnotes\":\"the post's notes\"}");
    }

    @Test
    void anotherSitesParentIs404() {
        SiteId other = TestData.site(data);
        long theirAdmin = member(other, "administrator");
        PostId theirs = post(other, "post", theirAdmin, "their post").insert(data);
        long theirRevision = revision(other, theirs, theirAdmin, "their v1", "their words", "2026-03-06T10:00:00Z");
        as(admin())
                .get(list(theirs))
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_parent"))
                .body("message", equalTo("Invalid post parent ID."));
        as(admin())
                .get(list(theirs) + "/" + theirRevision)
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_parent"));
        as(admin())
                .get(list(post7) + "/" + theirRevision)
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_id"))
                .body("message", equalTo("Invalid revision ID."));
        as(admin())
                .delete(list(post7) + "/" + theirRevision)
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_id"));
    }

    @Test
    void revisionsOffAnswersAnEmptyList() {
        SiteId quiet = TestData.siteIn(data, "Europe/Paris");
        String quietHost = Jdbc.strings(data, "select domain from qp_site where id = ?", quiet.value())
                .get(0);
        long quietAdmin = member(quiet, "administrator");
        PostId post20 = post(quiet, "post", quietAdmin, "post twenty").insert(data);
        long r21 = revision(quiet, post20, quietAdmin, "twenty v1", "twenty words", "2026-03-07T10:00:00Z");
        TestData.siteOption(data, quiet, "qp_features", "{\"revisions\": false}");
        String base = "http://" + quietHost + list(post20);
        Http.at(quietHost, "test-" + quietAdmin)
                .get(list(post20))
                .then()
                .statusCode(200)
                .header("X-WP-Total", equalTo("0"))
                .header("X-WP-TotalPages", equalTo("0"))
                .body(equalTo("[]"));
        Http.at(quietHost, "test-" + quietAdmin)
                .get(list(post20) + "?page=9")
                .then()
                .statusCode(200)
                .header("Link", equalTo("<" + base + "?page=0>; rel=\"prev\""))
                .body(equalTo("[]"));
        // the ordering checks still run
        Http.at(quietHost, "test-" + quietAdmin)
                .get(list(post20) + "?orderby=relevance")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_no_search_term_defined"));
        // the item still answers
        Http.at(quietHost, "test-" + quietAdmin)
                .get(list(post20) + "/" + r21)
                .then()
                .statusCode(200)
                .body("id", equalTo((int) r21));
    }
}
