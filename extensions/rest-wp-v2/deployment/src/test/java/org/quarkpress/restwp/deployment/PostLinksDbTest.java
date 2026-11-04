package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
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
import org.quarkpress.posts.PostType;

/**
 * The links of a post on a network's main site: WordPress's relations in its order, the revision count and the newest
 * revision, the parent of a child page, the term relations (absent for a type without REST taxonomies), the action
 * links of the edit context by the caller's capabilities (unfiltered HTML only for a super admin on a network), the
 * embeds of the author, the terms and the parent, and no featured media link until the media routes exist. Members of
 * the main site: alice (administrator and super admin), bob (administrator), carol (author), dave (contributor) and
 * erin (subscriber).
 */
class PostLinksDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
            PostLinksDbTest.class,
            BookType.class,
            NoteType.class,
            MemoType.class,
            PostFixtures.class,
            PostFixtures.Builder.class);

    /** A type in REST without authors, comments or revisions. */
    @PostType(
            key = "note",
            publicly = true,
            restBase = "notes",
            supports = {"title", "editor"})
    static final class NoteType {}

    /** A type not shown in REST (no REST base). */
    @PostType(
            key = "memo",
            publicly = true,
            supports = {"title", "editor"})
    static final class MemoType {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ROOT = "http://localhost/wp-json/wp/v2/";

    static AgroalDataSource data;
    static SiteId site;
    static long alice;
    static long bob;
    static long carol;
    static long dave;
    static long erin;
    static long uncategorized;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.MAIN;
        UserId admin = TestData.memberOfMain(data, "administrator");
        TestData.superAdmin(data, admin);
        alice = admin.value();
        bob = TestData.memberOfMain(data, "administrator").value();
        carol = TestData.memberOfMain(data, "author").value();
        dave = TestData.memberOfMain(data, "contributor").value();
        erin = TestData.memberOfMain(data, "subscriber").value();
        if (Jdbc.count(
                        data,
                        "select count(*) from qp_term where site_id = ? and taxonomy = 'category' and slug ="
                                + " 'uncategorized'",
                        site.value())
                == 0) {
            Jdbc.update(
                    data,
                    "insert into qp_term (site_id, taxonomy, name, slug, description) values (?, 'category',"
                            + " 'Uncategorized', 'uncategorized', '')",
                    site.value());
        }
        uncategorized = TestData.termId(data, site, "category", "uncategorized");
    }

    private static RequestSpecification as(Long user) {
        return Http.as(user == null ? null : "test-" + user);
    }

    private static PostFixtures.Builder post(String type, String status, long author, String title) {
        boolean floating = status.equals("draft") || status.equals("pending");
        return PostFixtures.builder(site)
                .type(type)
                .status(status)
                .author(author)
                .title(title)
                .publishedAt(floating ? null : "2026-03-01T10:00:00Z")
                .modifiedAt("2026-03-01T10:00:00Z");
    }

    private static PostId published(String type, long author) {
        return post(type, "publish", author, type + " " + UUID.randomUUID()).insert(data);
    }

    private static void assign(PostId post, long term) {
        Jdbc.update(
                data,
                "insert into qp_term_assignment (site_id, post_id, term_id) values (?, ?, ?)",
                site.value(),
                post.value(),
                term);
    }

    /** A regular revision row of the post, written past the services; answers its id. */
    private static long revision(PostId post, long author) {
        return revision(site, post, author);
    }

    /** A regular revision row of a post of {@code where}, written past the services; answers its id. */
    private static long revision(SiteId where, PostId post, long author) {
        String marker = "rev-" + UUID.randomUUID();
        Jdbc.update(
                data,
                "insert into qp_revision (site_id, post_id, author_id, title, content, excerpt, meta, autosave,"
                        + " autosave_owner, created_at) values (?, ?, ?, 'v1', 'v1', ?, " + TestDb.json()
                        + ", false, null, " + PostFixtures.instant() + ")",
                where.value(),
                post.value(),
                author,
                marker,
                "{}",
                PostFixtures.instantText("2026-03-01T11:00:00Z"));
        return Jdbc.count(data, "select id from qp_revision where excerpt = ?", marker);
    }

    private static JsonNode links(Long user, String path) {
        return json(as(user).get("/wp-json/wp/v2/" + path)
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .get("_links");
    }

    private static List<String> actions(JsonNode links) {
        List<String> actions = new ArrayList<>();
        for (String name : fieldNames(links)) {
            if (name.startsWith("wp:action-")) {
                actions.add(name.substring("wp:action-".length()));
            }
        }
        return actions;
    }

    @Test
    void linksOfAPublishedPostInWordPressOrder() {
        PostId post = published("post", carol);
        assign(post, uncategorized);
        JsonNode links = links(null, "posts/" + post.value());
        assertThat(fieldNames(links))
                .containsExactly(
                        "self",
                        "collection",
                        "about",
                        "author",
                        "replies",
                        "version-history",
                        "wp:attachment",
                        "wp:term",
                        "curies");
        String self = ROOT + "posts/" + post.value();
        assertThat(links.at("/self/0/href").asText()).isEqualTo(self);
        assertThat(links.at("/self/0/targetHints/allow").toString()).isEqualTo("[\"GET\"]");
        assertThat(links.at("/collection/0").toString()).isEqualTo("{\"href\":\"" + ROOT + "posts\"}");
        assertThat(links.at("/about/0").toString()).isEqualTo("{\"href\":\"" + ROOT + "types/post\"}");
        assertThat(links.at("/author/0").toString())
                .isEqualTo("{\"embeddable\":true,\"href\":\"" + ROOT + "users/" + carol + "\"}");
        assertThat(links.at("/replies/0").toString())
                .isEqualTo("{\"embeddable\":true,\"href\":\"" + ROOT + "comments?post=" + post.value() + "\"}");
        assertThat(links.at("/version-history/0").toString())
                .isEqualTo("{\"count\":0,\"href\":\"" + self + "/revisions\"}");
        assertThat(links.at("/wp:attachment/0").toString())
                .isEqualTo("{\"href\":\"" + ROOT + "media?parent=" + post.value() + "\"}");
        assertThat(links.get("wp:term")).hasSize(2);
        assertThat(links.at("/wp:term/0").toString())
                .isEqualTo("{\"taxonomy\":\"category\",\"embeddable\":true,\"href\":\"" + ROOT + "categories?post="
                        + post.value() + "\"}");
        assertThat(links.at("/wp:term/1").toString())
                .isEqualTo("{\"taxonomy\":\"post_tag\",\"embeddable\":true,\"href\":\"" + ROOT + "tags?post="
                        + post.value() + "\"}");
        assertThat(links.at("/curies").toString())
                .isEqualTo("[{\"name\":\"wp\",\"href\":\"https://api.w.org/{rel}\",\"templated\":true}]");
        // links are a field: not selected, not computed
        JsonNode selected = json(as(null).get("/wp-json/wp/v2/posts/" + post.value() + "?_fields=id,title")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(fieldNames(selected)).containsExactly("id", "title");
        JsonNode listed = json(as(null).get("/wp-json/wp/v2/posts?include=" + post.value())
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(fieldNames(listed.at("/0/_links"))).startsWith("self", "collection", "about", "author");

        // a revision: the count, then the newest revision as the predecessor
        long revision = revision(post, carol);
        JsonNode revised = links(null, "posts/" + post.value());
        assertThat(fieldNames(revised))
                .containsExactly(
                        "self",
                        "collection",
                        "about",
                        "author",
                        "replies",
                        "version-history",
                        "predecessor-version",
                        "wp:attachment",
                        "wp:term",
                        "curies");
        assertThat(revised.at("/version-history/0/count").asInt()).isEqualTo(1);
        assertThat(revised.at("/predecessor-version/0").toString())
                .isEqualTo("{\"id\":" + revision + ",\"href\":\"" + self + "/revisions/" + revision + "\"}");
    }

    @Test
    void actionLinksFollowTheCallersCapabilities() {
        PostId post = published("post", carol);
        String edit = "posts/" + post.value() + "?context=edit";
        JsonNode aliceLinks = links(alice, edit);
        assertThat(fieldNames(aliceLinks))
                .containsExactly(
                        "self",
                        "collection",
                        "about",
                        "author",
                        "replies",
                        "version-history",
                        "wp:attachment",
                        "wp:term",
                        "wp:action-publish",
                        "wp:action-unfiltered-html",
                        "wp:action-sticky",
                        "wp:action-assign-author",
                        "wp:action-create-categories",
                        "wp:action-assign-categories",
                        "wp:action-create-tags",
                        "wp:action-assign-tags",
                        "curies");
        String self = ROOT + "posts/" + post.value();
        assertThat(aliceLinks.at("/wp:action-publish/0").toString()).isEqualTo("{\"href\":\"" + self + "\"}");
        assertThat(aliceLinks.at("/wp:action-assign-tags/0").toString()).isEqualTo("{\"href\":\"" + self + "\"}");
        assertThat(actions(links(carol, edit)))
                .containsExactly("publish", "assign-categories", "create-tags", "assign-tags");
        // an administrator who is not a super admin: no unfiltered HTML on a network
        assertThat(actions(links(bob, edit)))
                .containsExactly(
                        "publish",
                        "sticky",
                        "assign-author",
                        "create-categories",
                        "assign-categories",
                        "create-tags",
                        "assign-tags");
        PostId davesDraft =
                post("post", "draft", dave, "draft " + UUID.randomUUID()).insert(data);
        assertThat(actions(links(dave, "posts/" + davesDraft.value() + "?context=edit")))
                .containsExactly("assign-categories", "create-tags", "assign-tags");
        PostId page = published("page", bob);
        assertThat(actions(links(alice, "pages/" + page.value() + "?context=edit")))
                .containsExactly("publish", "unfiltered-html", "assign-author");
        assertThat(actions(links(alice, "posts/" + post.value()))).isEmpty();
        assertThat(actions(links(alice, "posts/" + post.value() + "?context=embed")))
                .isEmpty();
    }

    @Test
    void childPagesLinkUpAndEmbedTheParent() {
        PostId parent = published("page", bob);
        PostId child = post("page", "publish", bob, "child " + UUID.randomUUID())
                .parent(parent)
                .insert(data);
        JsonNode links = links(null, "pages/" + child.value());
        assertThat(fieldNames(links))
                .containsExactly(
                        "self",
                        "collection",
                        "about",
                        "author",
                        "replies",
                        "version-history",
                        "up",
                        "wp:attachment",
                        "curies");
        assertThat(links.at("/up/0").toString())
                .isEqualTo("{\"embeddable\":true,\"href\":\"" + ROOT + "pages/" + parent.value() + "\"}");
        assertThat(fieldNames(links(null, "pages/" + parent.value()))).doesNotContain("up");
        JsonNode embedded = json(as(null).get("/wp-json/wp/v2/pages/" + child.value() + "?_embed=up")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .get("_embedded");
        assertThat(fieldNames(embedded)).containsExactly("up");
        assertThat(embedded.at("/up/0/id").asLong()).isEqualTo(parent.value());
        assertThat(fieldNames(embedded.at("/up/0"))).doesNotContain("content");
    }

    @Test
    void anEmbeddedProtectedParentStaysHidden() {
        PostId parent = post("page", "publish", bob, "locked parent " + UUID.randomUUID())
                .content("PARENT SECRET")
                .excerpt("PARENT EXCERPT")
                .password("pw")
                .insert(data);
        PostId child = post("page", "publish", bob, "child " + UUID.randomUUID())
                .parent(parent)
                .insert(data);
        // an embedded request carries no password and runs in the embed context, whoever asks
        for (Long caller : new Long[] {null, erin, bob}) {
            JsonNode up = json(as(caller)
                            .get("/wp-json/wp/v2/pages/" + child.value() + "?_embed=up")
                            .then()
                            .statusCode(200)
                            .extract()
                            .asString())
                    .at("/_embedded/up/0");
            assertThat(up.at("/id").asLong()).isEqualTo(parent.value());
            assertThat(up.at("/excerpt").toString()).isEqualTo("{\"rendered\":\"\",\"protected\":true}");
            assertThat(fieldNames(up)).doesNotContain("content");
            assertThat(up.toString()).doesNotContain("PARENT");
        }
    }

    @Test
    void embedsAuthorAndTerms() {
        PostId post = published("post", carol);
        assign(post, uncategorized);
        JsonNode body = json(as(null).get("/wp-json/wp/v2/posts/" + post.value() + "?_embed")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(fieldNames(body)).endsWith("_links", "_embedded");
        JsonNode embedded = body.get("_embedded");
        // replies lead to no route yet: their embed is that error (README D28); attachments are not embeddable
        assertThat(fieldNames(embedded)).containsExactly("author", "replies", "wp:term");
        assertThat(embedded.at("/replies/0/code").asText()).isEqualTo("rest_no_route");
        assertThat(embedded.at("/author/0/id").asLong()).isEqualTo(carol);
        assertThat(embedded.get("wp:term")).hasSize(2);
        assertThat(embedded.at("/wp:term/0/0/slug").asText()).isEqualTo("uncategorized");
        assertThat(embedded.at("/wp:term/0/0/taxonomy").asText()).isEqualTo("category");
        assertThat(embedded.at("/wp:term/1").toString()).isEqualTo("[]");
    }

    @Test
    void noFeaturedMediaLinkUntilMediaExists() {
        PostId post = published("post", carol);
        Jdbc.update(
                data,
                "update qp_post set meta = " + TestDb.json() + " where id = ?",
                "{\"_thumbnail_id\":[\"123\"]}",
                post.value());
        JsonNode body = json(as(alice)
                .get("/wp-json/wp/v2/posts/" + post.value() + "?_embed")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(body.get("featured_media").asLong()).isEqualTo(123);
        assertThat(fieldNames(body.get("_links"))).doesNotContain("wp:featuredmedia");
        assertThat(fieldNames(body.get("_embedded"))).doesNotContain("wp:featuredmedia");
    }

    @Test
    void aTypeWithoutRestTaxonomiesHasNoTermRelation() {
        PostId book = published("book", carol);
        assertThat(fieldNames(links(null, "books/" + book.value())))
                .containsExactly("self", "collection", "about", "author", "version-history", "wp:attachment", "curies");
        assertThat(links(null, "books/" + book.value()).at("/about/0/href").asText())
                .isEqualTo(ROOT + "types/book");
        assertThat(fieldNames(links(alice, "books/" + book.value() + "?context=edit")))
                .containsExactly(
                        "self",
                        "collection",
                        "about",
                        "author",
                        "version-history",
                        "wp:attachment",
                        "wp:action-publish",
                        "wp:action-unfiltered-html",
                        "wp:action-assign-author",
                        "curies");
    }

    @Test
    void revisionsOffLinkACountOfZero() {
        SiteId quiet = TestData.siteIn(data, "Europe/Paris");
        String quietHost = Jdbc.strings(data, "select domain from qp_site where id = ?", quiet.value())
                .get(0);
        UserId owner = TestData.profile(data, "author");
        TestData.member(data, quiet, owner, "[\"author\"]");
        PostId post = PostFixtures.builder(quiet)
                .type("post")
                .status("publish")
                .author(owner.value())
                .title("quiet " + UUID.randomUUID())
                .publishedAt("2026-03-01T10:00:00Z")
                .modifiedAt("2026-03-01T10:00:00Z")
                .insert(data);
        revision(quiet, post, owner.value());
        String path = "/wp-json/wp/v2/posts/" + post.value();
        JsonNode on = json(Http.at(quietHost, null)
                        .get(path)
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .get("_links");
        assertThat(on.at("/version-history/0/count").asInt()).isEqualTo(1);
        assertThat(fieldNames(on)).contains("predecessor-version");
        TestData.siteOption(data, quiet, "qp_features", "{\"revisions\": false}");
        JsonNode off = json(Http.at(quietHost, null)
                        .get(path)
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .get("_links");
        assertThat(off.at("/version-history/0").toString())
                .isEqualTo("{\"count\":0,\"href\":\"http://" + quietHost + path + "/revisions\"}");
        assertThat(fieldNames(off)).doesNotContain("predecessor-version");
    }

    @Test
    void aTypeWithoutAuthorsHasNoAuthorLinks() {
        PostId note = published("note", carol);
        assertThat(fieldNames(links(null, "notes/" + note.value())))
                .containsExactly("self", "collection", "about", "wp:attachment", "curies");
        assertThat(actions(links(alice, "notes/" + note.value() + "?context=edit")))
                .containsExactly("publish", "unfiltered-html");
    }

    @Test
    void aParentOfAnotherTypeLinksItsOwnRoute() {
        PostId book = published("book", bob);
        PostId underBook = post("page", "publish", bob, "under a book " + UUID.randomUUID())
                .parent(book)
                .insert(data);
        assertThat(links(null, "pages/" + underBook.value()).at("/up/0").toString())
                .isEqualTo("{\"embeddable\":true,\"href\":\"" + ROOT + "books/" + book.value() + "\"}");
        // a parent whose type is not shown in REST has no route to link
        PostId memo = published("memo", bob);
        PostId underMemo = post("page", "publish", bob, "under a memo " + UUID.randomUUID())
                .parent(memo)
                .insert(data);
        assertThat(fieldNames(links(null, "pages/" + underMemo.value()))).doesNotContain("up");
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
