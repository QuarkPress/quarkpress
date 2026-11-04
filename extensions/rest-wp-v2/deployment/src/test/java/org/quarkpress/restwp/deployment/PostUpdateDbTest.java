package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.core.deployment.spi.TaxonomyBuildItem;
import org.quarkpress.posts.PostMeta;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermsAssigned;

/**
 * {@code POST/PUT/PATCH /wp/v2/<base>/<id>}: WordPress's update checks in its order, the preparation of the request
 * against the stored post (status, dates, slugs of drafts and child pages, leaving the trash), the steps after the
 * row (format, featured image, sticky, template, terms) in one transaction with the revision saved once at its end,
 * and the 200 answer in the edit context. The site's zone is Europe/Paris and the clock is the test's; members:
 * alice (administrator and super admin), the editor bob, the author carol, the contributor dave and the subscriber
 * erin.
 */
class PostUpdateDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    PostUpdateDbTest.class,
                    PostUpdateDbTest.FailingTags.class,
                    PostUpdateDbTest.FootnotesWithTag.class,
                    BookType.class,
                    PostFixtures.class,
                    PostFixtures.Builder.class,
                    TestClock.class)
            .addBuildChainCustomizer(chain -> chain.addBuildStep(new BuildStep() {
                        @Override
                        public void execute(BuildContext context) {
                            context.produce(new TaxonomyBuildItem(lockedTaxonomy()));
                        }
                    })
                    .produces(TaxonomyBuildItem.class)
                    .build());

    /** A flat taxonomy of posts in REST whose terms only {@code manage_options} may assign. */
    static TaxonomyDefinition lockedTaxonomy() {
        return TaxonomyDefinition.builder("qp_locked", List.of("post"))
                .showInRest(true)
                .restBase("qp-locked")
                .capabilities(Map.of(
                        "manage_terms", "manage_categories",
                        "edit_terms", "manage_categories",
                        "delete_terms", "manage_categories",
                        "assign_terms", "manage_options"))
                .build();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The test clock's time: 12:00 in Paris. */
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    private static final String NOW_LOCAL = "2026-10-18T12:00:00";

    private static final String FOOTNOTES = "[{\"id\":\"a\",\"content\":\"note\"}]";

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long alice;
    static long bob;
    static long carol;
    static long dave;
    static long erin;
    static long defaultCategory;

    @Inject
    TestClock clock;

    /** Fails every assignment of tags while set: a step after the row that goes wrong. */
    @ApplicationScoped
    public static class FailingTags {

        static volatile boolean failing;

        void assigned(@Observes TermsAssigned event) {
            if (failing && event.taxonomy().equals("post_tag")) {
                throw new TermException("qp_test_tags_failed", "The tags could not be assigned.");
            }
        }
    }

    /**
     * Writes the revisioned footnotes meta while the tag {@link #tag} is assigned: a revisioned value written by a
     * step after the row, as the meta step writes one.
     */
    @ApplicationScoped
    public static class FootnotesWithTag {

        static volatile long tag;

        @Inject
        PostMeta meta;

        void assigned(@Observes TermsAssigned event) {
            if (tag > 0 && event.after().contains(TermId.of(tag))) {
                meta.update(event.post(), "footnotes", TextNode.valueOf(FOOTNOTES));
            }
        }
    }

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.siteIn(data, "Europe/Paris");
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        alice = member("administrator");
        TestData.superAdmin(data, UserId.of(alice));
        bob = member("editor");
        carol = member("author");
        dave = member("contributor");
        erin = member("subscriber");
        defaultCategory = term("category", "uncategorized");
        TestData.siteOption(data, site, "default_category", Long.toString(defaultCategory));
    }

    private static long member(String role) {
        UserId user = TestData.profile(data, role);
        TestData.member(data, site, user, "[\"" + role + "\"]");
        return user.value();
    }

    private static long term(String taxonomy, String slug) {
        Jdbc.update(
                data,
                "insert into qp_term (site_id, taxonomy, name, slug, description) values (?, ?, ?, ?, '')",
                site.value(),
                taxonomy,
                slug,
                slug);
        return TestData.termId(data, site, taxonomy, slug);
    }

    static RequestSpecification as(Long user) {
        return Http.at(host, user == null ? null : "test-" + user).contentType("application/json");
    }

    static Response put(Long user, String base, PostId post, String body) {
        return as(user).body(body).put("/wp-json/wp/v2/" + base + "/" + post.value());
    }

    /** A draft whose date floats, last modified 2026-09-01T12:00 in Paris. */
    private static PostFixtures.Builder draft(long author, String title) {
        return PostFixtures.builder(site)
                .status("draft")
                .author(author)
                .title(title)
                .publishedAt(null)
                .modifiedAt("2026-09-01T10:00:00Z");
    }

    private static PostFixtures.Builder published(long author, String title) {
        return PostFixtures.builder(site)
                .status("publish")
                .author(author)
                .title(title)
                .publishedAt("2026-09-01T10:00:00Z")
                .modifiedAt("2026-09-01T10:00:00Z");
    }

    private static String stored(PostId post, String column) {
        return Jdbc.strings(data, "select " + column + " from qp_post where id = ?", post.value())
                .get(0);
    }

    /** The post's revisions, autosaves included, as the revisions list counts them. */
    private static long revisions(PostId post) {
        return Long.parseLong(as(alice)
                .get("/wp-json/wp/v2/posts/" + post.value() + "/revisions")
                .then()
                .statusCode(200)
                .extract()
                .header("X-WP-Total"));
    }

    @Test
    void updateAnswersInEditContextAndAllowsTheSameMethods() {
        clock.set(NOW);
        String u = UUID.randomUUID().toString();
        PostId post = draft(carol, "draft " + u).insert(data);
        String item = "/wp-json/wp/v2/posts/" + post.value();
        // the edit context whatever was asked for; the writing methods in Allow
        as(carol)
                .body("{\"title\":\"New\"}")
                .put(item + "?context=view")
                .then()
                .statusCode(200)
                .header("Allow", "GET, POST, PUT, PATCH, DELETE")
                .header("Location", nullValue())
                .body("id", equalTo((int) post.value()))
                .body("title.raw", equalTo("New"))
                .body("password", equalTo(""))
                .body("status", equalTo("draft"));
        // POST, PATCH and the editor's POST with a method override are the same update
        as(carol)
                .body("{\"title\":\"By POST\"}")
                .post(item)
                .then()
                .statusCode(200)
                .header("Allow", "GET, POST, PUT, PATCH, DELETE")
                .body("title.raw", equalTo("By POST"));
        as(carol)
                .body("{\"title\":\"By PATCH\"}")
                .patch(item)
                .then()
                .statusCode(200)
                .body("title.raw", equalTo("By PATCH"));
        as(carol)
                .header("X-HTTP-Method-Override", "PUT")
                .body("{\"title\":\"By override\"}")
                .post(item)
                .then()
                .statusCode(200)
                .body("title.raw", equalTo("By override"));
        assertThat(stored(post, "title")).isEqualTo("By override");
        // fields the request does not name are left as they are
        put(carol, "posts", post, "{\"content\":\"Words\"}")
                .then()
                .statusCode(200)
                .body("title.raw", equalTo("By override"))
                .body("content.raw", equalTo("Words"));
    }

    @Test
    void permissionChecks() {
        clock.set(NOW);
        String u = UUID.randomUUID().toString();
        PostId carols = draft(carol, "carol's " + u).insert(data);
        as(null).body("{\"title\":\"x\"}")
                .put("/wp-json/wp/v2/posts/999999")
                .then()
                .statusCode(404)
                .header("Allow", nullValue())
                .body("code", equalTo("rest_post_invalid_id"))
                .body("message", equalTo("Invalid post ID."));
        // a post of another type is not on this route
        as(alice)
                .body("{\"title\":\"x\"}")
                .put("/wp-json/wp/v2/pages/" + carols.value())
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_id"));
        // the anonymous caller may not even read the draft: no method is allowed
        put(null, "posts", carols, "{\"title\":\"x\"}")
                .then()
                .statusCode(401)
                .header("Allow", nullValue())
                .body("code", equalTo("rest_cannot_edit"))
                .body("message", equalTo("Sorry, you are not allowed to edit this post."));
        put(dave, "posts", carols, "{\"title\":\"x\"}")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_edit"))
                .body("message", equalTo("Sorry, you are not allowed to edit this post."));
        // Allow is computed with the request's own parameters: the author refuses the update, not the delete
        put(carol, "posts", carols, "{\"author\":" + bob + "}")
                .then()
                .statusCode(403)
                .header("Allow", "GET, DELETE")
                .body("code", equalTo("rest_cannot_edit_others"))
                .body("message", equalTo("Sorry, you are not allowed to update posts as this user."));
        // her own id and 0 are no other author
        put(carol, "posts", carols, "{\"author\":" + carol + "}").then().statusCode(200);
        put(carol, "posts", carols, "{\"author\":0}").then().statusCode(200).body("author", equalTo((int) carol));
        // an unchanged stickiness needs no capability; a change does
        put(carol, "posts", carols, "{\"sticky\":false}").then().statusCode(200).body("sticky", equalTo(false));
        put(carol, "posts", carols, "{\"sticky\":true}")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_assign_sticky"))
                .body("message", equalTo("Sorry, you are not allowed to make posts sticky."));
        long locked = term("qp_locked", "locked-" + u);
        put(carol, "posts", carols, "{\"qp-locked\":[" + locked + "]}")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_assign_term"))
                .body("message", equalTo("Sorry, you are not allowed to assign the provided terms."));
        assertThat(stored(carols, "title")).isEqualTo("carol's " + u);
        // the editor may edit carol's post, make it sticky and give it to someone else
        put(bob, "posts", carols, "{\"sticky\":true,\"author\":" + dave + "}")
                .then()
                .statusCode(200)
                .body("sticky", equalTo(true))
                .body("author", equalTo((int) dave));
        // a sticky post stays sticky when sticky is not sent, and a false one unsticks it
        put(bob, "posts", carols, "{\"title\":\"still sticky\"}")
                .then()
                .statusCode(200)
                .body("sticky", equalTo(true));
        put(bob, "posts", carols, "{\"sticky\":false}").then().statusCode(200).body("sticky", equalTo(false));
    }

    /**
     * The sticky check compares with the post's stickiness: on her sticky post an author may send {@code true} (no
     * change) but not {@code false}.
     */
    @Test
    void theStickyCheckComparesWithTheStoredStickiness() {
        clock.set(NOW);
        String u = UUID.randomUUID().toString();
        PostId sticky = published(carol, "sticky " + u).sticky().insert(data);
        put(carol, "posts", sticky, "{\"sticky\":true}").then().statusCode(200).body("sticky", equalTo(true));
        put(carol, "posts", sticky, "{\"sticky\":false}")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_assign_sticky"))
                .body("message", equalTo("Sorry, you are not allowed to make posts sticky."));
        as(alice).get("/wp-json/wp/v2/posts/" + sticky.value()).then().body("sticky", equalTo(true));
    }

    /**
     * The checks and the status rule on the update path: another site's post is unknown; a failed edit check comes
     * before the author check; a contributor may not publish, schedule or privatise her own draft; a password set
     * through an update protects the post.
     */
    @Test
    void moreChecksOnTheUpdatePath() {
        clock.set(NOW);
        String u = UUID.randomUUID().toString();
        SiteId other = TestData.site(data);
        PostId elsewhere = PostFixtures.builder(other).title("elsewhere " + u).insert(data);
        put(alice, "posts", elsewhere, "{\"title\":\"x\"}")
                .then()
                .statusCode(404)
                .header("Allow", nullValue())
                .body("code", equalTo("rest_post_invalid_id"))
                .body("message", equalTo("Invalid post ID."));
        assertThat(stored(elsewhere, "title")).isEqualTo("elsewhere " + u);
        PostId carols = draft(carol, "carol's " + u).insert(data);
        // dave fails the edit check and the author check: the edit check answers
        put(dave, "posts", carols, "{\"author\":" + bob + "}")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_edit"));
        PostId daves = draft(dave, "dave's " + u).insert(data);
        put(dave, "posts", daves, "{\"status\":\"pending\"}")
                .then()
                .statusCode(200)
                .body("status", equalTo("pending"));
        for (String[] refusal : List.of(
                new String[] {
                    "{\"status\":\"publish\"}", "Sorry, you are not allowed to publish posts in this post type."
                },
                new String[] {
                    "{\"status\":\"future\",\"date\":\"2030-01-01T10:00:00\"}",
                    "Sorry, you are not allowed to publish posts in this post type."
                },
                new String[] {
                    "{\"status\":\"private\"}", "Sorry, you are not allowed to create private posts in this post type."
                })) {
            put(dave, "posts", daves, refusal[0])
                    .then()
                    .statusCode(403)
                    .body("code", equalTo("rest_cannot_publish"))
                    .body("message", equalTo(refusal[1]));
        }
        assertThat(stored(daves, "status")).isEqualTo("pending");
        put(carol, "posts", carols, "{\"password\":\"s3\",\"content\":\"secret\"}")
                .then()
                .statusCode(200)
                .body("password", equalTo("s3"))
                .body("content.protected", equalTo(true))
                .body("content.raw", equalTo("secret"));
        as(null).get("/wp-json/wp/v2/posts/" + carols.value()).then().statusCode(401);
        assertThat(stored(carols, "password")).isEqualTo("s3");
    }

    @Test
    void theEditorsFirstSaveOfAnAutoDraft() {
        clock.set(NOW);
        Jdbc.option(site, "default_post_format", "\"aside\"");
        long id;
        try {
            id = as(carol)
                    .body("{\"type\":\"post\"}")
                    .post("/wp-json/quarkpress/v1/drafts")
                    .then()
                    .statusCode(201)
                    .extract()
                    .jsonPath()
                    .getLong("id");
        } finally {
            Jdbc.deleteOption(site, "default_post_format");
        }
        PostId post = PostId.of(id);
        clock.plus(Duration.ofMinutes(5));
        // the request blanks the "Auto Draft" title, and nothing else is left
        put(carol, "posts", post, "{\"status\":\"draft\",\"title\":\"\"}")
                .then()
                .statusCode(400)
                .header("Allow", "GET, POST, PUT, PATCH, DELETE")
                .body("code", equalTo("empty_content"))
                .body("message", equalTo("Content, title, and excerpt are empty."))
                .body("data.status", equalTo(400));
        assertThat(stored(post, "status")).isEqualTo("auto-draft");
        JsonNode saved = json(put(
                        carol,
                        "posts",
                        post,
                        "{\"id\":" + id + ",\"title\":\"\",\"content\":\"g21 first save\",\"status\":\"draft\"}")
                .then()
                .statusCode(200)
                .extract()
                .asString());
        assertThat(saved.get("status").asText()).isEqualTo("draft");
        assertThat(saved.at("/title/raw").asText()).isEmpty();
        assertThat(saved.get("slug").asText()).isEmpty();
        assertThat(saved.get("format").asText()).isEqualTo("aside");
        // leaving the auto-draft status gives the default category
        assertThat(saved.get("categories").toString()).isEqualTo("[" + defaultCategory + "]");
        // the date still floats: now
        assertThat(saved.get("date").asText()).isEqualTo("2026-10-18T12:05:00");
        // under WordPress's /%postname%/ structure (the recorded site's) the id would read as a date archive: -2
        Jdbc.option(site, "permalink_structure", "\"/%postname%/\"");
        try {
            as(carol)
                    .get("/wp-json/wp/v2/posts/" + id + "?context=edit")
                    .then()
                    .statusCode(200)
                    .body("generated_slug", equalTo(id + "-2"));
        } finally {
            Jdbc.deleteOption(site, "permalink_structure");
        }
    }

    @Test
    void anUpdateMakesOneRevisionAfterItsMeta() {
        clock.set(NOW);
        String u = UUID.randomUUID().toString();
        PostId post = published(alice, "revisioned " + u).content("first").insert(data);
        long tag = term("post_tag", "footnotes-" + u);
        long before = revisions(post);
        FootnotesWithTag.tag = tag;
        try {
            put(alice, "posts", post, "{\"content\":\"changed\",\"tags\":[" + tag + "]}")
                    .then()
                    .statusCode(200)
                    .body("content.raw", equalTo("changed"));
        } finally {
            FootnotesWithTag.tag = 0;
        }
        assertThat(revisions(post)).isEqualTo(before + 1);
        JsonNode latest = json(as(alice)
                        .get("/wp-json/wp/v2/posts/" + post.value() + "/revisions?context=edit")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .get(0);
        assertThat(latest.at("/content/raw").asText()).isEqualTo("changed");
        // the meta written after the row is in the revision
        assertThat(latest.at("/meta/footnotes").asText()).isEqualTo(FOOTNOTES);
        // an update without fields still saves the post, and makes no revision of an unchanged post
        String modified = json(as(alice)
                        .get("/wp-json/wp/v2/posts/" + post.value() + "?context=edit")
                        .asString())
                .get("modified")
                .asText();
        clock.plus(Duration.ofSeconds(1));
        put(alice, "posts", post, "{}")
                .then()
                .statusCode(200)
                .body("modified", equalTo("2026-10-18T12:00:01"))
                .body("date", equalTo("2026-09-01T12:00:00"));
        assertThat(modified).isEqualTo(NOW_LOCAL);
        put(alice, "posts", post, "{\"id\":" + post.value() + "}").then().statusCode(200);
        assertThat(revisions(post)).isEqualTo(before + 1);
    }

    @Test
    void leavingTheTrashThroughAnUpdate() {
        clock.set(NOW);
        String u = UUID.randomUUID().toString();
        String slug = "hello-" + u;
        PostId trashed = PostFixtures.builder(site)
                .status("trash")
                .author(alice)
                .title("hello " + u)
                .slug(slug + "__trashed")
                .publishedAt("2026-09-01T10:00:00Z")
                .modifiedAt("2026-09-01T10:00:00Z")
                .insert(data);
        Jdbc.update(
                data,
                "update qp_post set meta = " + TestDb.json() + " where id = ?",
                "{\"_wp_desired_post_slug\":[\"" + slug + "\"],\"_wp_trash_meta_status\":[\"publish\"],"
                        + "\"_wp_trash_meta_time\":[1788256800]}",
                trashed.value());
        // the stored status may be sent back
        put(alice, "posts", trashed, "{\"status\":\"trash\",\"title\":\"still hello " + u + "\"}")
                .then()
                .statusCode(200)
                .body("status", equalTo("trash"))
                .body("slug", equalTo(slug + "__trashed"));
        put(alice, "posts", trashed, "{\"status\":\"draft\"}")
                .then()
                .statusCode(200)
                .body("status", equalTo("draft"))
                .body("slug", equalTo(slug))
                .body("permalink_template", equalTo("http://" + host + "/?p=" + trashed.value()));
        // trashing is DELETE's: a live post cannot be sent to the trash
        JsonNode refused = json(put(alice, "posts", trashed, "{\"status\":\"trash\"}")
                .then()
                .statusCode(400)
                .extract()
                .asString());
        assertThat(refused.get("code").asText()).isEqualTo("rest_invalid_param");
        assertThat(refused.get("message").asText()).isEqualTo("Invalid parameter(s): status");
        assertThat(refused.at("/data/params/status").asText())
                .isEqualTo("status is not one of publish, future, draft, pending, and private.");
        assertThat(refused.at("/data/details/status/code").asText()).isEqualTo("rest_not_in_enum");
        assertThat(stored(trashed, "status")).isEqualTo("draft");
        // a missing post's status is checked against the enum alone, and the post is then not found
        as(alice)
                .body("{\"status\":\"trash\"}")
                .put("/wp-json/wp/v2/posts/999999")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"));
    }

    @Test
    void childPageSlugUsesTheRequestsParent() {
        clock.set(NOW);
        String u = UUID.randomUUID().toString();
        PostId parent =
                published(alice, "parent").type("page").slug("parent-" + u).insert(data);
        String childA = "child-a-" + u;
        String top = "top-" + u;
        published(alice, "child a").type("page").slug(childA).parent(parent).insert(data);
        PostId childB = published(alice, "child b")
                .type("page")
                .slug("child-b-" + u)
                .parent(parent)
                .insert(data);
        published(alice, "top").type("page").slug(top).insert(data);
        // published: the post API's rule, against the stored parent
        put(alice, "pages", childB, "{\"slug\":\"" + childA + "\"}")
                .then()
                .statusCode(200)
                .body("slug", equalTo(childA + "-2"));
        put(alice, "pages", childB, "{\"slug\":\"" + top + "\"}")
                .then()
                .statusCode(200)
                .body("slug", equalTo(top));
        // a draft is made unique as published, against the request's parent: none means the top level
        put(alice, "pages", childB, "{\"slug\":\"" + childA + "\",\"status\":\"draft\"}")
                .then()
                .statusCode(200)
                .body("status", equalTo("draft"))
                .body("slug", equalTo(childA))
                .body("parent", equalTo((int) parent.value()));
        put(
                        alice,
                        "pages",
                        childB,
                        "{\"slug\":\"" + childA + "\",\"status\":\"draft\",\"parent\":" + parent.value() + "}")
                .then()
                .statusCode(200)
                .body("slug", equalTo(childA + "-2"));
        put(alice, "pages", childB, "{\"slug\":\"" + top + "\",\"status\":\"draft\"}")
                .then()
                .statusCode(200)
                .body("slug", equalTo(top + "-2"));
        // the stored draft status counts when the request sends none
        put(alice, "pages", childB, "{\"slug\":\"" + top + "\"}")
                .then()
                .statusCode(200)
                .body("slug", equalTo(top + "-2"));
    }

    @Test
    void dateRulesOnUpdate() {
        clock.set(NOW);
        String u = UUID.randomUUID().toString();
        // a floating draft re-sent its own date is dated now
        PostId floating = draft(alice, "floating " + u).insert(data);
        as(alice)
                .get("/wp-json/wp/v2/posts/" + floating.value() + "?context=edit")
                .then()
                .statusCode(200)
                .body("date", equalTo("2026-09-01T12:00:00"))
                .body("date_gmt", equalTo("2026-09-01T10:00:00"));
        put(alice, "posts", floating, "{\"date\":\"2026-09-01T12:00:00\"}")
                .then()
                .statusCode(200)
                .body("date", equalTo(NOW_LOCAL))
                .body("modified", equalTo(NOW_LOCAL));
        // a date with an offset is an instant; the same date again leaves the dates alone, and the save still moves
        // the modification date (g12's two answers came within one second; g22: every update saves the post)
        PostId dated = draft(alice, "dated " + u).insert(data);
        put(alice, "posts", dated, "{\"date\":\"2026-03-01T09:00:00+05:00\"}")
                .then()
                .statusCode(200)
                .body("date", equalTo("2026-03-01T05:00:00"))
                .body("date_gmt", equalTo("2026-03-01T04:00:00"))
                .body("status", equalTo("draft"));
        clock.plus(Duration.ofSeconds(5));
        put(alice, "posts", dated, "{\"date\":\"2026-03-01T09:00:00+05:00\"}")
                .then()
                .statusCode(200)
                .body("date", equalTo("2026-03-01T05:00:00"))
                .body("date_gmt", equalTo("2026-03-01T04:00:00"))
                .body("modified", equalTo("2026-10-18T12:00:05"));
        // the draft no longer floats: a later update moves only its modification date
        clock.plus(Duration.ofMinutes(1));
        put(alice, "posts", dated, "{\"title\":\"dated again " + u + "\"}")
                .then()
                .statusCode(200)
                .body("date", equalTo("2026-03-01T05:00:00"))
                .body("modified", equalTo("2026-10-18T12:01:05"));
        clock.set(NOW);
        // a null date re-dates a published post to now
        PostId post = published(alice, "published " + u).insert(data);
        put(alice, "posts", post, "{\"date\":null}")
                .then()
                .statusCode(200)
                .body("status", equalTo("publish"))
                .body("date", equalTo(NOW_LOCAL))
                .body("date_gmt", equalTo("2026-10-18T10:00:00"));
        // a date ahead schedules it; publish alone keeps it scheduled
        put(alice, "posts", post, "{\"date\":\"2030-01-01T10:00:00\"}")
                .then()
                .statusCode(200)
                .body("status", equalTo("future"))
                .body("date_gmt", equalTo("2030-01-01T09:00:00"));
        put(alice, "posts", post, "{\"status\":\"publish\"}")
                .then()
                .statusCode(200)
                .body("status", equalTo("future"));
        // a null GMT date publishes it now
        clock.plus(Duration.ofMinutes(2));
        put(alice, "posts", post, "{\"date_gmt\":null}")
                .then()
                .statusCode(200)
                .body("status", equalTo("publish"))
                .body("date", equalTo("2026-10-18T12:02:00"))
                .body("date_gmt", equalTo("2026-10-18T10:02:00"));
    }

    /**
     * A null {@code date} or {@code date_gmt} clears both dates: the post is dated now, and its date floats when the
     * status it gets is draft or pending, whatever it had before (a floating draft, a dated draft, a published post sent
     * back to draft).
     */
    @Test
    void nullDatesFloatWhenTheResultingStatusFloats() {
        clock.set(NOW);
        String u = UUID.randomUUID().toString();
        PostId floating = draft(alice, "floating null " + u).insert(data);
        put(alice, "posts", floating, "{\"date\":null}")
                .then()
                .statusCode(200)
                .body("status", equalTo("draft"))
                .body("date", equalTo(NOW_LOCAL));
        assertThat(floats(floating)).isTrue();
        PostId dated = draft(alice, "dated null " + u)
                .publishedAt("2026-03-01T08:00:00Z")
                .insert(data);
        put(alice, "posts", dated, "{\"date_gmt\":null}")
                .then()
                .statusCode(200)
                .body("status", equalTo("draft"))
                .body("date", equalTo(NOW_LOCAL))
                .body("date_gmt", equalTo("2026-10-18T10:00:00"));
        assertThat(floats(dated)).isTrue();
        // floating again: the next update dates it now again
        clock.plus(Duration.ofMinutes(3));
        put(alice, "posts", dated, "{\"title\":\"still floating " + u + "\"}")
                .then()
                .statusCode(200)
                .body("date", equalTo("2026-10-18T12:03:00"));
        clock.set(NOW);
        PostId post = published(alice, "back to draft " + u).insert(data);
        put(alice, "posts", post, "{\"status\":\"draft\",\"date\":null}")
                .then()
                .statusCode(200)
                .body("status", equalTo("draft"))
                .body("date", equalTo(NOW_LOCAL));
        assertThat(floats(post)).isTrue();
        // a date sent next to a null one is ignored
        PostId pending = PostFixtures.builder(site)
                .status("pending")
                .author(alice)
                .title("pending " + u)
                .publishedAt("2026-03-01T08:00:00Z")
                .modifiedAt("2026-03-01T08:00:00Z")
                .insert(data);
        put(alice, "posts", pending, "{\"date\":\"2026-05-01T10:00:00\",\"date_gmt\":null}")
                .then()
                .statusCode(200)
                .body("status", equalTo("pending"))
                .body("date", equalTo(NOW_LOCAL));
        assertThat(floats(pending)).isTrue();
    }

    /** Whether the post's date floats: no GMT date stored. */
    private static boolean floats(PostId post) {
        return Jdbc.count(data, "select count(*) from qp_post where id = ? and published_at is null", post.value())
                == 1;
    }

    @Test
    void aFailureRollsBackTheWholeUpdate() {
        clock.set(NOW);
        String u = UUID.randomUUID().toString();
        PostId post = published(alice, "kept " + u).content("first").insert(data);
        long tag = term("post_tag", "failing-" + u);
        long before = revisions(post);
        FailingTags.failing = true;
        try {
            put(
                            alice,
                            "posts",
                            post,
                            "{\"title\":\"changed " + u + "\",\"content\":\"changed\",\"sticky\":true,"
                                    + "\"format\":\"aside\",\"tags\":[" + tag + "]}")
                    .then()
                    .statusCode(500)
                    .body("code", equalTo("qp_test_tags_failed"))
                    .body("message", equalTo("The tags could not be assigned."));
        } finally {
            FailingTags.failing = false;
        }
        // WordPress keeps the row, its stickiness and its format and makes no revision; QuarkPress keeps nothing
        assertThat(stored(post, "title")).isEqualTo("kept " + u);
        assertThat(stored(post, "content")).isEqualTo("first");
        as(alice)
                .get("/wp-json/wp/v2/posts/" + post.value() + "?context=edit")
                .then()
                .statusCode(200)
                .body("sticky", equalTo(false))
                .body("format", equalTo("standard"))
                .body("tags", equalTo(List.of()));
        assertThat(revisions(post)).isEqualTo(before);
    }

    static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
