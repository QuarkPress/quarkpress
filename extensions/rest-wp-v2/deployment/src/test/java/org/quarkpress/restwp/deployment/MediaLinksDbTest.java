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
import org.quarkpress.core.UserId;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.posts.PostType;

/**
 * The links of an attachment on a network's main site: WordPress's relations in its order (the attachment itself, its
 * collection, its type, its author, its comments, its cover image when the caller may see it, the post it is attached
 * to, then the action links of the edit context), never the revision, parent, attachment, publish or sticky links of
 * posts, the API root as the target of a parent whose type is not shown in REST (still embeddable), and the embeds of
 * the author and the parent with the parent route's own permission check (a synced pattern parent's included).
 * Members of the main site: alice (administrator and super admin), bob (editor), carol (author) and erin
 * (subscriber).
 */
class MediaLinksDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            RestApp.network(MediaLinksDbTest.class, HiddenType.class, PostFixtures.class, PostFixtures.Builder.class);

    /** A public type not shown in REST (no REST base). */
    @PostType(key = "hidden", publicly = true)
    static final class HiddenType {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String API = "http://localhost/wp-json/";

    private static final String ROOT = API + "wp/v2/";

    private static final String CURIES = "[{\"name\":\"wp\",\"href\":\"https://api.w.org/{rel}\",\"templated\":true}]";

    static AgroalDataSource data;
    static UserId alice;
    static UserId bob;
    static UserId carol;
    static UserId erin;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        alice = TestData.memberOfMain(data, "administrator");
        TestData.superAdmin(data, alice);
        bob = TestData.memberOfMain(data, "editor");
        carol = TestData.memberOfMain(data, "author");
        erin = TestData.memberOfMain(data, "subscriber");
    }

    @Test
    void aViewAttachmentWithoutParentHasFiveLinks() {
        MediaSnapshot image = upload(bob, "v");
        long id = image.post().id().value();
        JsonNode links = get(null, id + "").get("_links");
        assertThat(fieldNames(links)).containsExactly("self", "collection", "about", "author", "replies");
        String self = ROOT + "media/" + id;
        assertThat(links.at("/self/0/href").asText()).isEqualTo(self);
        assertThat(links.at("/self/0/targetHints/allow").toString()).isEqualTo("[\"GET\"]");
        assertThat(links.at("/collection/0").toString()).isEqualTo("{\"href\":\"" + ROOT + "media\"}");
        assertThat(links.at("/about/0").toString()).isEqualTo("{\"href\":\"" + ROOT + "types/attachment\"}");
        assertThat(links.at("/author/0").toString())
                .isEqualTo("{\"embeddable\":true,\"href\":\"" + ROOT + "users/" + bob.value() + "\"}");
        assertThat(links.at("/replies/0").toString())
                .isEqualTo("{\"embeddable\":true,\"href\":\"" + ROOT + "comments?post=" + id + "\"}");
        // links are a field: not selected, not computed
        assertThat(fieldNames(get(null, id + "?_fields=id,title"))).containsExactly("id", "title");
    }

    @Test
    void attachedToCarriesTypeAndId() {
        String u = suffix();
        PostId post = post("post", "publish", bob, "post " + u);
        MediaSnapshot onPost = upload(bob, "p", post);
        JsonNode links = get(null, onPost.post().id().value() + "").get("_links");
        assertThat(fieldNames(links))
                .containsExactly("self", "collection", "about", "author", "replies", "wp:attached-to", "curies");
        assertThat(links.at("/wp:attached-to").toString())
                .isEqualTo("[{\"embeddable\":true,\"post_type\":\"post\",\"id\":" + post.value() + ",\"href\":\"" + ROOT
                        + "posts/" + post.value() + "\"}]");
        assertThat(links.at("/curies").toString()).isEqualTo(CURIES);

        PostId page = post("page", "publish", bob, "page " + u);
        MediaSnapshot onPage = upload(bob, "g", page);
        assertThat(get(null, onPage.post().id().value() + "")
                        .at("/_links/wp:attached-to")
                        .toString())
                .isEqualTo("[{\"embeddable\":true,\"post_type\":\"page\",\"id\":" + page.value() + ",\"href\":\"" + ROOT
                        + "pages/" + page.value() + "\"}]");

        // trashed: the parent is kept and still linked
        Http.as(bearer(alice))
                .delete("/wp-json/wp/v2/posts/" + post.value())
                .then()
                .statusCode(200);
        JsonNode trashed = get(bob, onPost.post().id().value() + "");
        assertThat(trashed.get("post").asLong()).isEqualTo(post.value());
        assertThat(trashed.at("/_links/wp:attached-to/0/id").asLong()).isEqualTo(post.value());

        // deleted for good: the attachment is handed down to no parent, unlinked
        Http.as(bearer(alice))
                .delete("/wp-json/wp/v2/pages/" + page.value() + "?force=true")
                .then()
                .statusCode(200);
        JsonNode orphan = get(null, onPage.post().id().value() + "");
        assertThat(orphan.get("post").isNull()).isTrue();
        assertThat(fieldNames(orphan.get("_links")))
                .containsExactly("self", "collection", "about", "author", "replies");
    }

    @Test
    void aParentWithoutRestRouteLinksTheApiRoot() {
        PostId hidden = post("hidden", "publish", bob, "hidden " + suffix());
        MediaSnapshot attached = upload(bob, "h", hidden);
        JsonNode links = get(bob, attached.post().id().value() + "").get("_links");
        assertThat(links.at("/wp:attached-to").toString())
                .isEqualTo("[{\"embeddable\":true,\"post_type\":\"hidden\",\"id\":" + hidden.value() + ",\"href\":\""
                        + API + "\"}]");
        // still embeddable: the API root is embedded as any other target, the request answering 200
        JsonNode embedded = get(bob, attached.post().id().value() + "?_embed=wp:attached-to");
        assertThat(embedded.at("/_embedded/wp:attached-to/0").has("namespaces")).isTrue();
        assertThat(embedded.at("/_embedded/wp:attached-to/0").has("code")).isFalse();
    }

    @Test
    void linksFollowWordPresssOrder() {
        PostId post = post("post", "publish", bob, "ordered " + suffix());
        MediaSnapshot attached = upload(bob, "r", post);
        cover(attached, upload(bob, "q").post().id().value());
        assertThat(fieldNames(get(alice, attached.post().id().value() + "?context=edit")
                        .get("_links")))
                .containsExactly(
                        "self",
                        "collection",
                        "about",
                        "author",
                        "replies",
                        "wp:featuredmedia",
                        "wp:attached-to",
                        "wp:action-unfiltered-html",
                        "wp:action-assign-author",
                        "curies");
    }

    @Test
    void editContextActionsFollowTheCaller() {
        MediaSnapshot carols = upload(carol, "e");
        long id = carols.post().id().value();
        String self = ROOT + "media/" + id;
        JsonNode aliceLinks = get(alice, id + "?context=edit").get("_links");
        assertThat(fieldNames(aliceLinks))
                .containsExactly(
                        "self",
                        "collection",
                        "about",
                        "author",
                        "replies",
                        "wp:action-unfiltered-html",
                        "wp:action-assign-author",
                        "curies");
        assertThat(aliceLinks.at("/wp:action-unfiltered-html").toString()).isEqualTo("[{\"href\":\"" + self + "\"}]");
        assertThat(aliceLinks.at("/wp:action-assign-author").toString()).isEqualTo("[{\"href\":\"" + self + "\"}]");
        assertThat(aliceLinks.at("/curies").toString()).isEqualTo(CURIES);
        // the view context has no actions
        assertThat(fieldNames(get(alice, id + "").get("_links")))
                .containsExactly("self", "collection", "about", "author", "replies");
        // the author on her own attachment may take neither action
        assertThat(fieldNames(get(carol, id + "?context=edit").get("_links")))
                .containsExactly("self", "collection", "about", "author", "replies");
        // an editor may assign another author, not post unfiltered HTML on a network
        assertThat(fieldNames(get(bob, id + "?context=edit").get("_links")))
                .containsExactly(
                        "self", "collection", "about", "author", "replies", "wp:action-assign-author", "curies");
    }

    @Test
    void theCoverImageIsLinkedWhenTheCallerMaySeeIt() {
        String u = suffix();
        MediaSnapshot song = upload(bob, "s");
        long id = song.post().id().value();
        // an unattached cover is published in effect: linked for everyone
        long open = upload(bob, "o").post().id().value();
        cover(song, open);
        JsonNode links = get(null, id + "").get("_links");
        assertThat(fieldNames(links))
                .containsExactly("self", "collection", "about", "author", "replies", "wp:featuredmedia", "curies");
        assertThat(links.at("/wp:featuredmedia").toString())
                .isEqualTo("[{\"embeddable\":true,\"href\":\"" + ROOT + "media/" + open + "\"}]");
        // a cover attached to a draft: only for a caller who may read it
        PostId draft = post("post", "draft", bob, "draft " + u);
        long closed = upload(bob, "c", draft).post().id().value();
        cover(song, closed);
        assertThat(fieldNames(get(null, id + "").get("_links"))).doesNotContain("wp:featuredmedia");
        assertThat(fieldNames(get(erin, id + "").get("_links"))).doesNotContain("wp:featuredmedia");
        assertThat(get(bob, id + "").at("/_links/wp:featuredmedia/0/href").asText())
                .isEqualTo(ROOT + "media/" + closed);
        // a cover that no longer exists is not linked, though featured_media still names it
        cover(song, 999_999_999L);
        JsonNode stale = get(bob, id + "");
        assertThat(stale.get("featured_media").asLong()).isEqualTo(999_999_999L);
        assertThat(fieldNames(stale.get("_links"))).doesNotContain("wp:featuredmedia");
    }

    @Test
    void embedsFollowWordPress() {
        String u = suffix();
        PostId post = post("post", "publish", bob, "embedded " + u);
        MediaSnapshot attached = upload(bob, "m", post);
        JsonNode embedded = get(null, attached.post().id().value() + "?_embed=author,wp:attached-to")
                .get("_embedded");
        assertThat(fieldNames(embedded)).containsExactly("author", "wp:attached-to");
        assertThat(embedded.at("/author/0/id").asLong()).isEqualTo(bob.value());
        assertThat(embedded.at("/author/0").has("avatar_urls")).isTrue();
        assertThat(embedded.at("/wp:attached-to/0/id").asLong()).isEqualTo(post.value());
        // the parent in the embed context: no status, no content
        assertThat(embedded.at("/wp:attached-to/0").has("status")).isFalse();
        assertThat(embedded.at("/wp:attached-to/0").has("content")).isFalse();
        assertThat(embedded.at("/wp:attached-to/0/title/rendered").asText()).isEqualTo("embedded " + u);

        // a synced pattern parent embeds as WordPress embeds it: refused to the anonymous caller (the type's read
        // capability), in the embed context for bob
        PostId pattern = post("wp_block", "publish", bob, "pattern " + u);
        long onPattern = upload(bob, "b", pattern).post().id().value();
        assertThat(get(null, onPattern + "?_embed=wp:attached-to")
                        .at("/_embedded/wp:attached-to")
                        .toString())
                .isEqualTo("[{\"code\":\"rest_forbidden\",\"message\":\"Sorry, you are not allowed to do that.\","
                        + "\"data\":{\"status\":401}}]");
        JsonNode embeddedPattern =
                get(bob, onPattern + "?_embed=wp:attached-to").at("/_embedded/wp:attached-to/0");
        assertThat(fieldNames(embeddedPattern))
                .containsExactly(
                        "id", "date", "slug", "type", "link", "title", "excerpt", "wp_pattern_sync_status", "_links");
        assertThat(embeddedPattern.get("title").toString()).isEqualTo("[]");

        // carol's attachment of bob's draft: carol reads her attachment, not its parent
        PostId draft = post("post", "draft", bob, "draft " + u);
        long carols = upload(carol, "d").post().id().value();
        Jdbc.update(data, "update qp_post set parent_id = ? where id = ?", draft.value(), carols);
        JsonNode refused = get(carol, carols + "?_embed=wp:attached-to");
        assertThat(refused.at("/_links/wp:attached-to/0/id").asLong()).isEqualTo(draft.value());
        assertThat(refused.at("/_embedded/wp:attached-to").toString())
                .isEqualTo("[{\"code\":\"rest_forbidden\",\"message\":\"Sorry, you are not allowed to do that.\","
                        + "\"data\":{\"status\":403}}]");
    }

    private static MediaSnapshot upload(UserId user, String prefix) {
        return MediaFixturesForRest.upload(TestData.MAIN, user, prefix + suffix() + ".jpg", false);
    }

    private static MediaSnapshot upload(UserId user, String prefix, PostId parent) {
        return MediaFixturesForRest.upload(
                TestData.MAIN, user, prefix + suffix() + ".jpg", false, upload -> upload.parent(parent));
    }

    /** Names {@code cover} as the attachment's cover image ({@code _thumbnail_id}), written past the services. */
    private static void cover(MediaSnapshot attachment, long cover) {
        Jdbc.update(
                data,
                "update qp_post set meta = " + TestDb.json() + " where id = ?",
                "{\"_thumbnail_id\":[\"" + cover + "\"]}",
                attachment.post().id().value());
    }

    private static PostId post(String type, String status, UserId author, String title) {
        boolean floating = status.equals("draft");
        return PostFixtures.builder(TestData.MAIN)
                .type(type)
                .status(status)
                .author(author.value())
                .title(title)
                .publishedAt(floating ? null : "2026-03-01T10:00:00Z")
                .modifiedAt("2026-03-01T10:00:00Z")
                .insert(data);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String bearer(UserId user) {
        return "test-" + user.value();
    }

    private static JsonNode get(UserId user, String target) {
        RequestSpecification as = Http.as(user == null ? null : bearer(user));
        return json(as.get("/wp-json/wp/v2/media/" + target)
                .then()
                .statusCode(200)
                .extract()
                .asString());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
