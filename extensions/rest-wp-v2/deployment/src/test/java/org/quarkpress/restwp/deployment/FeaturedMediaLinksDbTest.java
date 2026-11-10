package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostType;

/**
 * The featured image of a post as WordPress links and embeds it: {@code wp:featuredmedia} (embeddable, after
 * {@code up} and before {@code wp:attachment}) to the target's own route when the post names one that exists, whose
 * type is shown in REST, and that is published in effect or readable by the caller, while the media routes answer on
 * the site; {@code _embed} then embeds the attachment in the embed context. {@code featured_media} keeps the stored id
 * whether linked or not. Posts and pages link alike; a featured image is read once per request however many listed
 * posts name it. Non-images are refused silently by the write. Members of the main site: alice (administrator and super
 * admin), bob (editor), carol (author) and erin (subscriber).
 */
class FeaturedMediaLinksDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    FeaturedMediaLinksDbTest.class, HiddenType.class, PostFixtures.class, PostFixtures.Builder.class)
            // SVG is not among WordPress's upload types; a site that adds it may feature it
            .overrideRuntimeConfigKey("quarkpress.media.allowed-types.\"svg\"", "image/svg+xml");

    /** A public type not shown in REST (no REST base). */
    @PostType(key = "hidden", publicly = true)
    static final class HiddenType {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ROOT = "http://localhost/wp-json/wp/v2/";

    /** The keys of an attachment in the embed context, in WordPress's order. */
    private static final String[] EMBED_KEYS = {
        "id",
        "date",
        "slug",
        "type",
        "link",
        "title",
        "author",
        "featured_media",
        "caption",
        "alt_text",
        "media_type",
        "mime_type",
        "media_details",
        "source_url",
        "_links"
    };

    static AgroalDataSource data;
    static UserId alice;
    static UserId bob;
    static UserId carol;
    static UserId erin;

    @Inject
    @PersistenceUnit("quarkpress")
    SessionFactory sessions;

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
    void anUnattachedFeaturedImageIsLinkedForEveryone() {
        long image = upload(TestData.MAIN, bob, "loose.jpg", null);
        PostId post = post(TestData.MAIN, "post", "publish", carol, null);
        feature(post, image);
        for (UserId caller : new UserId[] {null, erin, alice}) {
            JsonNode body = get(caller, "posts/" + post.value());
            assertThat(body.get("featured_media").asLong()).isEqualTo(image);
            JsonNode links = body.get("_links");
            assertThat(fieldNames(links))
                    .containsExactly(
                            "self",
                            "collection",
                            "about",
                            "author",
                            "replies",
                            "version-history",
                            "wp:featuredmedia",
                            "wp:attachment",
                            "wp:term",
                            "curies");
            assertThat(links.get("wp:featuredmedia").toString())
                    .isEqualTo("[{\"embeddable\":true,\"href\":\"" + ROOT + "media/" + image + "\"}]");
        }
        JsonNode embedded =
                get(null, "posts/" + post.value() + "?_embed=wp:featuredmedia").get("_embedded");
        assertThat(fieldNames(embedded)).containsExactly("wp:featuredmedia");
        assertThat(embedded.get("wp:featuredmedia")).hasSize(1);
        JsonNode attachment = embedded.at("/wp:featuredmedia/0");
        assertThat(fieldNames(attachment)).containsExactly(EMBED_KEYS);
        assertThat(attachment.get("id").asLong()).isEqualTo(image);
        assertThat(attachment.get("media_type").asText()).isEqualTo("image");
        assertThat(attachment.at("/media_details/width").asInt()).isEqualTo(64);
        assertThat(attachment.at("/_links/self/0/href").asText()).isEqualTo(ROOT + "media/" + image);
        // the whole embed asks for it too, in the embed context
        JsonNode all =
                get(alice, "posts/" + post.value() + "?context=edit&_embed").get("_embedded");
        assertThat(fieldNames(all)).contains("wp:featuredmedia");
        assertThat(fieldNames(all.at("/wp:featuredmedia/0"))).containsExactly(EMBED_KEYS);

        // a child page: after up, before wp:attachment
        PostId parent = post(TestData.MAIN, "page", "publish", bob, null);
        PostId child = post(TestData.MAIN, "page", "publish", bob, parent);
        feature(child, image);
        assertThat(fieldNames(get(null, "pages/" + child.value()).get("_links")))
                .containsExactly(
                        "self",
                        "collection",
                        "about",
                        "author",
                        "replies",
                        "version-history",
                        "up",
                        "wp:featuredmedia",
                        "wp:attachment",
                        "curies");
    }

    @Test
    void aHiddenFeaturedImageIsNotLinkedForAnonymous() {
        PostId draft = post(TestData.MAIN, "post", "draft", bob, null);
        long onDraft = upload(TestData.MAIN, bob, "on-draft.jpg", draft);
        long privateImage = upload(TestData.MAIN, bob, "private.jpg", null);
        Jdbc.update(data, "update qp_post set status = 'private' where id = ?", privateImage);
        for (long image : new long[] {onDraft, privateImage}) {
            PostId post = post(TestData.MAIN, "post", "publish", carol, null);
            feature(post, image);
            String target = "posts/" + post.value() + "?_embed";
            for (UserId caller : new UserId[] {null, erin, carol}) {
                JsonNode body = get(caller, target);
                assertThat(body.get("featured_media").asLong()).isEqualTo(image);
                assertThat(fieldNames(body.get("_links"))).doesNotContain("wp:featuredmedia");
                assertThat(fieldNames(body.get("_embedded"))).doesNotContain("wp:featuredmedia");
            }
            JsonNode seen = get(alice, target);
            assertThat(seen.at("/_links/wp:featuredmedia/0/href").asText()).isEqualTo(ROOT + "media/" + image);
            JsonNode attachment = seen.at("/_embedded/wp:featuredmedia/0");
            assertThat(attachment.get("id").asLong()).isEqualTo(image);
            assertThat(fieldNames(attachment)).containsExactly(EMBED_KEYS);
        }
        // the embedded image of a draft links to its attachment page by id
        PostId post = post(TestData.MAIN, "post", "publish", carol, null);
        feature(post, onDraft);
        assertThat(get(alice, "posts/" + post.value() + "?_embed=wp:featuredmedia")
                        .at("/_embedded/wp:featuredmedia/0/link")
                        .asText())
                .endsWith("?attachment_id=" + onDraft);
    }

    @Test
    void aStaleIdKeepsTheValueWithoutLink() {
        long image = upload(TestData.MAIN, bob, "gone.jpg", null);
        Http.as("test-" + alice.value())
                .delete("/wp-json/wp/v2/media/" + image + "?force=true")
                .then()
                .statusCode(200);
        PostId post = post(TestData.MAIN, "post", "publish", carol, null);
        // past the services: deleting the attachment cleared every featured id naming it
        feature(post, image);
        JsonNode body = get(alice, "posts/" + post.value() + "?_embed");
        assertThat(body.get("featured_media").asLong()).isEqualTo(image);
        assertThat(fieldNames(body.get("_links"))).doesNotContain("wp:featuredmedia");
        assertThat(fieldNames(body.get("_embedded"))).doesNotContain("wp:featuredmedia");
    }

    @Test
    void noLinkWhenTheMediaFeatureIsOff() {
        SiteId site = TestData.site(data);
        TestData.member(data, site, bob, "[\"editor\"]");
        String host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        long image = upload(site, bob, "off.jpg", null);
        PostId post = post(site, "post", "publish", bob, null);
        feature(post, image);
        String path = "/wp-json/wp/v2/posts/" + post.value() + "?_embed";
        JsonNode on = json(
                Http.at(host, null).get(path).then().statusCode(200).extract().asString());
        assertThat(on.at("/_links/wp:featuredmedia/0/href").asText())
                .isEqualTo("http://" + host + "/wp-json/wp/v2/media/" + image);
        assertThat(on.at("/_embedded/wp:featuredmedia/0/id").asLong()).isEqualTo(image);

        TestData.siteOption(data, site, "qp_features", "{\"media\": false}");
        try {
            JsonNode off = json(Http.at(host, null)
                    .get(path)
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString());
            assertThat(off.get("featured_media").asLong()).isEqualTo(image);
            assertThat(fieldNames(off.get("_links"))).doesNotContain("wp:featuredmedia");
            assertThat(fieldNames(off.get("_embedded"))).doesNotContain("wp:featuredmedia");
        } finally {
            TestData.siteOption(data, site, "qp_features", "{\"media\": true}");
        }
    }

    @Test
    void postsAndPagesLinkTheTargetsOwnRouteAndATypeOutsideRestHasNone() {
        long image = upload(TestData.MAIN, bob, "shared.jpg", null);
        PostId post = post(TestData.MAIN, "post", "publish", carol, null);
        PostId page = post(TestData.MAIN, "page", "publish", bob, null);
        feature(post, image);
        feature(page, image);
        String link = "[{\"embeddable\":true,\"href\":\"" + ROOT + "media/" + image + "\"}]";
        assertThat(get(null, "posts/" + post.value())
                        .at("/_links/wp:featuredmedia")
                        .toString())
                .isEqualTo(link);
        assertThat(get(null, "pages/" + page.value())
                        .at("/_links/wp:featuredmedia")
                        .toString())
                .isEqualTo(link);
        assertThat(get(null, "pages/" + page.value() + "?_embed=wp:featuredmedia")
                        .at("/_embedded/wp:featuredmedia/0/id")
                        .asLong())
                .isEqualTo(image);

        // past the services: a featured id naming a page links the page's route
        PostId pictured = post(TestData.MAIN, "post", "publish", carol, null);
        feature(pictured, page.value());
        assertThat(get(null, "posts/" + pictured.value())
                        .at("/_links/wp:featuredmedia")
                        .toString())
                .isEqualTo("[{\"embeddable\":true,\"href\":\"" + ROOT + "pages/" + page.value() + "\"}]");
        // one naming a post of a type not shown in REST has no route to link
        PostId hidden = post(TestData.MAIN, "hidden", "publish", bob, null);
        feature(pictured, hidden.value());
        JsonNode outside = get(alice, "posts/" + pictured.value());
        assertThat(outside.get("featured_media").asLong()).isEqualTo(hidden.value());
        assertThat(fieldNames(outside.get("_links"))).doesNotContain("wp:featuredmedia");
    }

    @Test
    void eachFeaturedImageIsReadOncePerRequest() {
        // the same posts without featured images: the reads every listed post costs anyway
        SiteId plain = TestData.site(data);
        TestData.member(data, plain, bob, "[\"editor\"]");
        for (int i = 0; i < 11; i++) {
            post(plain, "post", "publish", bob, null);
        }
        SiteId site = TestData.site(data);
        TestData.member(data, site, bob, "[\"editor\"]");
        PostId parent = post(site, "post", "publish", bob, null);
        long cover = upload(site, bob, "cover.jpg", parent);
        for (int i = 0; i < 10; i++) {
            feature(post(site, "post", "publish", bob, null), cover);
        }
        Statistics statistics = statistics();
        long before = postReads(statistics);
        assertThat(list(plain)).hasSize(11);
        long baseline = postReads(statistics) - before;

        before = postReads(statistics);
        JsonNode listed = list(site);
        long reads = postReads(statistics) - before;
        assertThat(listed).hasSize(11);
        int linked = 0;
        for (JsonNode item : listed) {
            if (item.at("/_links/wp:featuredmedia/0/href").asText().endsWith("/media/" + cover)) {
                linked++;
            }
        }
        assertThat(linked).isEqualTo(10);
        // one read of the featured image and one of the post it is attached to (its effective status)
        assertThat(reads - baseline).isEqualTo(2);
    }

    @Test
    void nonImagesAreRefusedSilently() {
        long jpeg = upload(TestData.MAIN, bob, "first.jpg", null);
        long song = upload(TestData.MAIN, bob, "song.mp3", null);
        long pdf = upload(TestData.MAIN, bob, "paper.pdf", null);
        long svg = svg(bob);
        PostId post = post(TestData.MAIN, "post", "publish", carol, null);

        JsonNode set = put(post, jpeg);
        assertThat(set.get("featured_media").asLong()).isEqualTo(jpeg);
        assertThat(set.at("/_links/wp:featuredmedia/0/href").asText()).isEqualTo(ROOT + "media/" + jpeg);
        // an MP3 is no image: the featured image goes, and the answer is still 200
        JsonNode audio = put(post, song);
        assertThat(audio.get("featured_media").asLong()).isZero();
        assertThat(fieldNames(audio.get("_links"))).doesNotContain("wp:featuredmedia");
        assertThat(put(post, jpeg).get("featured_media").asLong()).isEqualTo(jpeg);
        // a PDF has no preview to feature
        JsonNode paper = put(post, pdf);
        assertThat(paper.get("featured_media").asLong()).isZero();
        assertThat(fieldNames(paper.get("_links"))).doesNotContain("wp:featuredmedia");
        // an SVG is an image, without sizes or dimensions
        JsonNode vector = put(post, svg);
        assertThat(vector.get("featured_media").asLong()).isEqualTo(svg);
        assertThat(vector.at("/_links/wp:featuredmedia/0/href").asText()).isEqualTo(ROOT + "media/" + svg);
        assertThat(get(null, "posts/" + post.value()).get("featured_media").asLong())
                .isEqualTo(svg);
    }

    // ---- helpers

    private static long upload(SiteId site, UserId user, String name, PostId parent) {
        return MediaFixturesForRest.upload(
                        site, user, name, false, upload -> parent == null ? upload : upload.parent(parent))
                .post()
                .id()
                .value();
    }

    /**
     * An SVG uploaded by {@code user} through the REST route, as a raw body, while the network's upload types name
     * {@code svg} as well.
     */
    private static long svg(UserId user) {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><rect width=\"10\""
                + " height=\"10\"/></svg>";
        String types = "update qp_network_option set value = " + TestDb.json() + " where name = 'upload_filetypes'";
        String saved = Jdbc.strings(data, "select value from qp_network_option where name = 'upload_filetypes'")
                .get(0);
        Jdbc.update(data, types, "\"jpg jpeg png gif svg\"");
        try {
            Response created = Http.as("test-" + user.value())
                    .contentType("image/svg+xml")
                    .header("Content-Disposition", "attachment; filename=\"vector-" + suffix() + ".svg\"")
                    .body(svg.getBytes(StandardCharsets.UTF_8))
                    .post("/wp-json/wp/v2/media");
            assertThat(created.statusCode()).as(created.asString()).isEqualTo(201);
            return json(created.asString()).get("id").asLong();
        } finally {
            Jdbc.update(data, types, saved);
        }
    }

    private static PostId post(SiteId site, String type, String status, UserId author, PostId parent) {
        boolean floating = status.equals("draft");
        PostFixtures.Builder builder = PostFixtures.builder(site)
                .type(type)
                .status(status)
                .author(author.value())
                .title(type + " " + suffix())
                .publishedAt(floating ? null : "2026-03-01T10:00:00Z")
                .modifiedAt("2026-03-01T10:00:00Z");
        return (parent == null ? builder : builder.parent(parent)).insert(data);
    }

    /** Names {@code featured} as the post's featured image, past the services. */
    private static void feature(PostId post, long featured) {
        Jdbc.update(
                data,
                "update qp_post set meta = " + TestDb.json() + " where id = ?",
                "{\"_thumbnail_id\":[\"" + featured + "\"]}",
                post.value());
    }

    private static JsonNode put(PostId post, long featured) {
        Response answer = Http.as("test-" + alice.value())
                .contentType("application/json")
                .body("{\"featured_media\":" + featured + "}")
                .put("/wp-json/wp/v2/posts/" + post.value());
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(200);
        return json(answer.asString());
    }

    private static JsonNode get(UserId user, String target) {
        Response answer = Http.as(user == null ? null : "test-" + user.value()).get("/wp-json/wp/v2/" + target);
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(200);
        return json(answer.asString());
    }

    private static JsonNode list(SiteId site) {
        String host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        Response answer = Http.at(host, null).get("/wp-json/wp/v2/posts?per_page=20&_fields=id,_links");
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(200);
        return json(answer.asString());
    }

    /** The persistence unit's statistics, switched on. */
    private Statistics statistics() {
        Statistics statistics = sessions.getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    /** The executions of the posts module's read of one post by site and id. */
    private static long postReads(Statistics statistics) {
        long count = 0;
        for (String query : statistics.getQueries()) {
            if (query.contains("PostEntity") && query.contains("siteId = ?1 and id = ?2")) {
                count += statistics.getQueryStatistics(query).getExecutionCount();
            }
        }
        return count;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node != null) {
            node.fieldNames().forEachRemaining(names::add);
        }
        return names;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
