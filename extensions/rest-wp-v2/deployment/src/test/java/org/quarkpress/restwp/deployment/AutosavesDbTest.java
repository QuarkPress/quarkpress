package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.UserId;
import org.quarkpress.core.deployment.spi.TaxonomyBuildItem;

/**
 * The autosaves routes ({@code /wp/v2/<base>/<id>/autosaves} and {@code /wp/v2/<base>/<parent>/autosaves/<id>}):
 * the own-draft branch that updates the post and answers it in the revision shape, the caller's autosave on every
 * other post, the lock that sends the author's own request to her autosave, the list of every user's autosaves with
 * the caller's preview token, the item that answers the latest autosave whatever its id, the refusals, the edit
 * context forced on the answer, the footnotes comparison and a child page's own draft. Members of the site (UTC):
 * the administrator alice, the editor bob, the author carol, the contributor dave and the subscriber erin; the clock
 * is the test's.
 */
class AutosavesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
                    AutosavesDbTest.class,
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

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    private static final String EDITOR_BODY =
            "{\"title\":\"%s\",\"content\":\"%s\",\"excerpt\":\"\"," + "\"meta\":{\"footnotes\":\"\"}}";

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long alice;
    static long bob;
    static long carol;
    static long dave;
    static long erin;

    @Inject
    TestClock clock;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.site(data);
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        alice = member("administrator");
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

    @Test
    void ownDraftBranchUpdatesThePostAndAnswersItInTheRevisionShape() {
        clock.set(NOW);
        PostId post = PostFixtures.builder(site)
                .status("auto-draft")
                .author(carol)
                .slug("")
                .title("Auto Draft")
                .publishedAt(null)
                .insert(data);
        // the post's own status passes the status check, though it is not one a post may be given
        autosave(carol, post, "{\"status\":\"auto-draft\",\"content\":\"C0\"}")
                .then()
                .statusCode(200)
                .body("content.raw", equalTo("C0"));
        autosave(carol, post, "{\"status\":\"inherit\"}")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"));
        Response saved = autosave(
                carol,
                post,
                "{\"title\":\"T\",\"content\":\"C\",\"excerpt\":\"\","
                        + "\"meta\":{\"footnotes\":\"[{\\\"content\\\":\\\"a note\\\",\\\"id\\\":\\\"n1\\\"}]\"},"
                        + "\"status\":\"draft\"}");
        saved.then().statusCode(200).header("Location", nullValue()).header("Allow", equalTo("GET, POST"));
        JsonNode body = json(saved);
        assertThat(fieldNames(body))
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
                        "preview_link");
        assertThat(body.get("id").asLong()).isEqualTo(post.value());
        assertThat(body.get("author").asLong()).isEqualTo(carol);
        assertThat(body.get("parent").asLong()).isZero();
        assertThat(body.get("slug").asText()).isEmpty();
        assertThat(body.get("date").isNull()).isTrue();
        assertThat(body.get("date_gmt").isNull()).isTrue();
        assertThat(body.get("modified").asText()).isEqualTo("2026-10-18T10:00:00");
        assertThat(body.at("/title/raw").asText()).isEqualTo("T");
        assertThat(body.at("/content/raw").asText()).isEqualTo("C");
        assertThat(body.get("meta").toString()).isEqualTo("{\"footnotes\":\"\"}");
        assertThat(body.get("preview_link").asText()).isEqualTo(home() + "?p=" + post.value() + "&preview=true");
        as(carol)
                .get("/wp-json/wp/v2/posts/" + post.value() + "?context=edit")
                .then()
                .statusCode(200)
                .body("status", equalTo("draft"))
                .body("title.raw", equalTo("T"))
                .body("content.raw", equalTo("C"));
        // the own-draft branch writes no autosave and no regular revision, and leaves the post's meta alone
        as(carol).get(list(post) + "?context=edit").then().statusCode(200).body(equalTo("[]"));
        assertThat(Jdbc.count(data, "select count(*) from qp_revision where post_id = ?", post.value()))
                .isZero();
        assertThat(json(Jdbc.strings(data, "select meta from qp_post where id = ?", post.value())
                                .get(0))
                        .has("footnotes"))
                .isFalse();
    }

    @Test
    void theCallersOwnLockKeepsTheOwnDraftBranch() {
        clock.set(NOW);
        PostId draft = PostFixtures.builder(site)
                .status("draft")
                .author(carol)
                .title("carol's locked draft")
                .content("first words")
                .publishedAt(null)
                .insert(data);
        as(carol)
                .post("/wp-json/quarkpress/v1/locks/" + draft.value())
                .then()
                .statusCode(200)
                .body("activePostLock", equalTo(NOW.getEpochSecond() + ":" + carol));
        autosave(carol, draft, "{\"content\":\"carol under her own lock\"}")
                .then()
                .statusCode(200)
                .body("parent", equalTo(0))
                .body("slug", equalTo(slug(draft)));
        assertThat(content(draft)).isEqualTo("carol under her own lock");
    }

    @Test
    void anotherSitesPostIsNotFound() {
        clock.set(NOW);
        SiteId other = TestData.site(data);
        PostId elsewhere = PostFixtures.builder(other).author(alice).insert(data);
        autosave(alice, elsewhere, "{\"content\":\"x\"}")
                .then()
                .statusCode(404)
                .body(equalTo(error("rest_post_invalid_id", "Invalid post ID.", 404)));
        for (String target : List.of(list(elsewhere), list(elsewhere) + "/1")) {
            as(alice)
                    .get(target)
                    .then()
                    .statusCode(404)
                    .body(equalTo(error("rest_post_invalid_parent", "Invalid post parent ID.", 404)));
        }
    }

    @Test
    void anAuthorsFootnotesAreFilteredInHerAutosave() {
        clock.set(NOW);
        PostId post = PostFixtures.builder(site)
                .author(carol)
                .title("carol's noted post")
                .content("words")
                .insert(data);
        String footnotes = "[{\\\"content\\\":\\\"<script>alert(1)</script>kept\\\",\\\"id\\\":\\\"n1\\\"}]";
        JsonNode saved =
                json(autosave(carol, post, "{\"content\":\"words\",\"meta\":{\"footnotes\":\"" + footnotes + "\"}}"));
        String stored = saved.at("/meta/footnotes").asText();
        assertThat(stored).contains("kept").contains("n1").doesNotContain("<script");
        // the row holds the filtered text
        assertThat(Jdbc.strings(data, "select meta from qp_revision where post_id = ?", post.value())
                        .get(0))
                .doesNotContain("<script");
    }

    @Test
    void otherBranchesWriteTheCallersAutosave() {
        clock.set(NOW);
        PostId draft = PostFixtures.builder(site)
                .status("draft")
                .author(carol)
                .title("carol's draft")
                .content("carol's words")
                .publishedAt(null)
                .insert(data);
        Response saved = autosave(bob, draft, "{\"title\":\"bob's title\",\"content\":\"bob's words\"}");
        saved.then().statusCode(200);
        JsonNode body = json(saved);
        assertThat(body.get("parent").asLong()).isEqualTo(draft.value());
        assertThat(body.get("slug").asText()).isEqualTo(draft.value() + "-autosave-v1");
        assertThat(body.get("author").asLong()).isEqualTo(bob);
        assertThat(body.get("date").asText()).isEqualTo("2026-10-18T10:00:00");
        assertThat(body.at("/content/raw").asText()).isEqualTo("bob's words");
        assertThat(body.at("/excerpt/raw").asText()).isEmpty();
        String token = token(body.get("preview_link").asText());
        assertThat(token).hasSize(27);
        assertThat(body.get("preview_link").asText())
                .isEqualTo(home() + "?p=" + draft.value() + "&preview_id=" + draft.value() + "&preview_nonce=" + token
                        + "&preview=true");
        assertThat(body.at("/_links/parent/0/href").asText())
                .isEqualTo("http://" + host + "/wp-json/wp/v2/posts/" + draft.value());
        assertThat(fieldNames(body.get("_links"))).containsExactly("parent");
        as(alice)
                .get("/wp-json/wp/v2/posts/" + draft.value() + "?context=edit")
                .then()
                .statusCode(200)
                .body("status", equalTo("draft"))
                .body("title.raw", equalTo("carol's draft"))
                .body("content.raw", equalTo("carol's words"));

        // carol's published post: her own autosave, the status in the request not applied
        PostId published = PostFixtures.builder(site)
                .author(carol)
                .title("carol's post")
                .content("published words")
                .insert(data);
        Response own = autosave(carol, published, "{\"content\":\"carol's new words\",\"status\":\"draft\"}");
        own.then()
                .statusCode(200)
                .body("author", equalTo((int) carol))
                .body("parent", equalTo((int) published.value()))
                .body("slug", equalTo(published.value() + "-autosave-v1"));
        as(carol)
                .get("/wp-json/wp/v2/posts/" + published.value() + "?context=edit")
                .then()
                .statusCode(200)
                .body("status", equalTo("publish"))
                .body("content.raw", equalTo("published words"));
    }

    @Test
    void aLockedDraftAutosavesIntoTheCallersRevision() {
        clock.set(NOW);
        PostId draft = PostFixtures.builder(site)
                .status("draft")
                .author(carol)
                .title("locked draft")
                .content("first words")
                .publishedAt(null)
                .insert(data);
        String bobsLock = NOW.getEpochSecond() + ":" + bob;
        as(bob).post("/wp-json/quarkpress/v1/locks/" + draft.value())
                .then()
                .statusCode(200)
                .body("activePostLock", equalTo(bobsLock));

        Response locked = autosave(carol, draft, "{\"content\":\"carol under the lock\"}");
        locked.then()
                .statusCode(200)
                .body("author", equalTo((int) carol))
                .body("slug", equalTo(draft.value() + "-autosave-v1"));
        assertThat(json(locked).get("parent").asLong()).isEqualTo(draft.value());
        assertThat(content(draft)).isEqualTo("first words");

        as(bob).contentType("application/json")
                .body("{\"active_post_lock\":\"" + bobsLock + "\"}")
                .delete("/wp-json/quarkpress/v1/locks/" + draft.value())
                .then()
                .statusCode(200)
                .body("released", equalTo(true));
        // the release leaves the lock five seconds to live: still the autosave until they pass
        clock.plus(Duration.ofSeconds(4));
        Response stillLocked = autosave(carol, draft, "{\"content\":\"carol four seconds later\"}");
        stillLocked.then().statusCode(200).body("slug", equalTo(draft.value() + "-autosave-v1"));
        assertThat(content(draft)).isEqualTo("first words");
        clock.plus(Duration.ofSeconds(1));
        Response free = autosave(carol, draft, "{\"content\":\"carol after the lock\"}");
        free.then().statusCode(200).body("parent", equalTo(0)).body("slug", equalTo(slug(draft)));
        assertThat(json(free).get("id").asLong()).isEqualTo(draft.value());
        assertThat(content(draft)).isEqualTo("carol after the lock");
    }

    @Test
    void listAndItem() {
        clock.set(NOW);
        PostId post = PostFixtures.builder(site)
                .author(alice)
                .title("alice's post")
                .content("words")
                .insert(data);
        long daves = autosaveRow(post, dave, "dave's words", "2026-03-01T12:05:00Z");
        long alices = autosaveRow(post, alice, "alice's words", "2026-03-01T12:09:00Z");

        long tokensBefore = tokenRows();
        Response first = as(alice).get(list(post) + "?context=edit");
        first.then()
                .statusCode(200)
                .header("X-WP-Total", nullValue())
                .header("X-WP-TotalPages", nullValue())
                .header("Allow", equalTo("GET, POST"));
        JsonNode items = json(first);
        assertThat(ids(items)).containsExactly(alices, daves);
        assertThat(items.get(1).get("author").asLong()).isEqualTo(dave);
        String token = token(items.get(0).get("preview_link").asText());
        assertThat(token).hasSize(27);
        // every item carries the caller's token, whoever wrote the autosave
        assertThat(token(items.get(1).get("preview_link").asText())).isEqualTo(token);
        // another caller's list of the same post carries that caller's own token
        String bobs = token(json(as(bob).get(list(post) + "?context=edit"))
                .get(0)
                .get("preview_link")
                .asText());
        assertThat(bobs).hasSize(27).isNotEqualTo(token);
        assertThat(fieldNames(items.get(0)))
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
                        "preview_link",
                        "_links");
        // a second list within the same tick: the same token, and nothing stored for it
        JsonNode again = json(as(alice).get(list(post) + "?context=edit"));
        assertThat(token(again.get(0).get("preview_link").asText())).isEqualTo(token);
        assertThat(tokenRows()).isEqualTo(tokensBefore);
        // the view context has no preview link
        JsonNode view = json(as(alice).get(list(post)));
        assertThat(view.get(0).has("preview_link")).isFalse();
        assertThat(view.get(0).at("/content/raw").isMissingNode()).isTrue();
        as(alice)
                .head(list(post))
                .then()
                .statusCode(200)
                .header("X-WP-Total", nullValue())
                .body(equalTo(""));

        // the item: the latest autosave of any user, whatever the id
        for (String id : List.of(String.valueOf(daves), "123456")) {
            Response item = as(alice).get(list(post) + "/" + id + "?context=edit");
            item.then().statusCode(200).header("Allow", equalTo("GET"));
            assertThat(json(item).get("id").asLong()).isEqualTo(alices);
            assertThat(token(json(item).get("preview_link").asText())).isEqualTo(token);
        }
        as(alice)
                .get(list(post) + "/" + alices)
                .then()
                .statusCode(200)
                .body("id", equalTo((int) alices))
                .body("preview_link", nullValue());

        // refusals
        as(dave).get(list(post))
                .then()
                .statusCode(403)
                .body(equalTo(
                        error("rest_cannot_read", "Sorry, you are not allowed to view autosaves of this post.", 403)));
        as(dave).get(list(post) + "/" + alices)
                .then()
                .statusCode(403)
                .body(equalTo(
                        error("rest_cannot_read", "Sorry, you are not allowed to view revisions of this post.", 403)));
        as(null).get(list(post)).then().statusCode(401).body("code", equalTo("rest_cannot_read"));
        for (String target :
                List.of("/wp-json/wp/v2/posts/999999/autosaves", "/wp-json/wp/v2/posts/999999/autosaves/" + alices)) {
            as(alice)
                    .get(target)
                    .then()
                    .statusCode(404)
                    .body(equalTo(error("rest_post_invalid_parent", "Invalid post parent ID.", 404)));
        }
        PostId aPage = PostFixtures.builder(site).type("page").author(alice).insert(data);
        as(alice)
                .get("/wp-json/wp/v2/posts/" + aPage.value() + "/autosaves")
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_parent"));

        // a post without autosaves
        PostId bare = PostFixtures.builder(site).author(alice).insert(data);
        as(alice).get(list(bare)).then().statusCode(200).body(equalTo("[]"));
        as(alice)
                .get(list(bare) + "/1")
                .then()
                .statusCode(404)
                .body(equalTo(error("rest_post_no_autosave", "There is no autosave revision for this post.", 404)));
    }

    @Test
    void createErrors() {
        clock.set(NOW);
        PostId draft = PostFixtures.builder(site)
                .status("draft")
                .author(carol)
                .title("carol's errors")
                .content("words")
                .publishedAt(null)
                .insert(data);
        as(carol)
                .contentType("application/json")
                .body("{\"content\":\"x\"}")
                .post("/wp-json/wp/v2/posts/0/autosaves")
                .then()
                .statusCode(404)
                .body(equalTo(error("rest_post_invalid_id", "Invalid item ID.", 404)));
        as(carol)
                .contentType("application/json")
                .body("{\"content\":\"x\"}")
                .post("/wp-json/wp/v2/posts/999999/autosaves")
                .then()
                .statusCode(404)
                .body(equalTo(error("rest_post_invalid_id", "Invalid post ID.", 404)));
        autosave(erin, draft, "{\"content\":\"x\"}")
                .then()
                .statusCode(403)
                .body(equalTo(error("rest_cannot_edit", "Sorry, you are not allowed to edit this post.", 403)));
        autosave(dave, draft, "{\"content\":\"x\"}").then().statusCode(403).body("code", equalTo("rest_cannot_edit"));
        autosave(null, draft, "{\"content\":\"x\"}").then().statusCode(401).body("code", equalTo("rest_cannot_edit"));
        // validation comes first, even for a post that does not exist
        as(carol)
                .contentType("application/json")
                .body("{\"status\":\"nope\"}")
                .post("/wp-json/wp/v2/posts/999999/autosaves")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"))
                .body(
                        "data.params.status",
                        equalTo("status is not one of publish, future, draft, pending, and private."));
        // the author of the draft may not name another author (WordPress refuses it in its check)
        autosave(carol, draft, "{\"content\":\"x\",\"author\":999999}")
                .then()
                .statusCode(403)
                .body(equalTo(error(
                        "rest_cannot_edit_others", "Sorry, you are not allowed to update posts as this user.", 403)));
        autosave(carol, draft, "{\"password\":\"x\",\"sticky\":true}")
                .then()
                .statusCode(403)
                .body(equalTo(
                        error("rest_cannot_assign_sticky", "Sorry, you are not allowed to make posts sticky.", 403)));
        // an editor passes the check; the preparation's error is the answer (README D32: WordPress saves without
        // the fields)
        autosave(bob, draft, "{\"content\":\"x\",\"author\":999999}")
                .then()
                .statusCode(400)
                .body(equalTo(error("rest_invalid_author", "Invalid author ID.", 400)));
        autosave(bob, draft, "{\"password\":\"x\",\"sticky\":true}")
                .then()
                .statusCode(400)
                .body(equalTo(error("rest_invalid_field", "A post can not be sticky and have a password.", 400)));
        // a term the caller may not assign (the shared update checks)
        long locked = term("qp_locked", "locked-" + UUID.randomUUID());
        autosave(carol, draft, "{\"content\":\"x\",\"qp-locked\":[" + locked + "]}")
                .then()
                .statusCode(403)
                .body(equalTo(error(
                        "rest_cannot_assign_term", "Sorry, you are not allowed to assign the provided terms.", 403)));
        // nothing was written
        as(carol).get(list(draft) + "?context=edit").then().statusCode(200).body(equalTo("[]"));
        assertThat(content(draft)).isEqualTo("words");
    }

    @Test
    void contextIsForcedToEdit() {
        clock.set(NOW);
        PostId post = PostFixtures.builder(site)
                .author(carol)
                .title("carol's forced post")
                .content("words")
                .insert(data);
        Response view = as(carol)
                .contentType("application/json")
                .body("{\"content\":\"carol in view\"}")
                .post(list(post) + "?context=view");
        view.then()
                .statusCode(200)
                .header("Location", nullValue())
                .header("Allow", equalTo("GET, POST"))
                .body("content.raw", equalTo("carol in view"))
                .body("guid.raw", equalTo(home() + "?p=" + json(view).get("id").asLong()));
        assertThat(json(view).get("preview_link").asText())
                .startsWith(home() + "?p=" + post.value() + "&preview_id=" + post.value() + "&preview_nonce=");
        Response embed = as(carol)
                .contentType("application/json")
                .body("{\"content\":\"carol in embed\"}")
                .post(list(post) + "?context=embed&_fields=id,preview_link,title");
        embed.then().statusCode(200);
        JsonNode body = json(embed);
        assertThat(fieldNames(body)).containsExactly("id", "title", "preview_link");
        assertThat(body.get("title").toString()).isEqualTo("{\"raw\":\"\",\"rendered\":\"\"}");
    }

    @Test
    void identicalTextWithoutAFootnotesRowStillBumpsTheAutosave() {
        clock.set(NOW);
        PostId post = PostFixtures.builder(site)
                .author(carol)
                .title("f10 post")
                .content("f10 text")
                .insert(data);
        String same = EDITOR_BODY.formatted("f10 post", "f10 text");
        JsonNode created = json(autosave(carol, post, same));
        long id = created.get("id").asLong();
        assertThat(created.get("date").asText()).isEqualTo("2026-10-18T10:00:00");

        clock.plus(Duration.ofMinutes(1));
        JsonNode bumped = json(autosave(carol, post, same));
        assertThat(bumped.get("id").asLong()).isEqualTo(id);
        assertThat(dates(bumped))
                .containsExactly(
                        "2026-10-18T10:01:00", "2026-10-18T10:01:00", "2026-10-18T10:01:00", "2026-10-18T10:01:00");

        // a body without meta compares the texts alone: untouched
        clock.plus(Duration.ofMinutes(1));
        JsonNode withoutMeta =
                json(autosave(carol, post, "{\"title\":\"f10 post\",\"content\":\"f10 text\",\"excerpt\":\"\"}"));
        assertThat(withoutMeta.get("id").asLong()).isEqualTo(id);
        assertThat(withoutMeta.get("date").asText()).isEqualTo("2026-10-18T10:01:00");

        // once the post stores the key, even empty, the identical request leaves the autosave untouched
        Jdbc.update(
                data,
                "update qp_post set meta = " + TestDb.json() + " where id = ?",
                "{\"footnotes\":[\"\"]}",
                post.value());
        clock.plus(Duration.ofMinutes(1));
        JsonNode untouched = json(autosave(carol, post, same));
        assertThat(untouched.get("id").asLong()).isEqualTo(id);
        assertThat(dates(untouched))
                .containsExactly(
                        "2026-10-18T10:01:00", "2026-10-18T10:01:00", "2026-10-18T10:01:00", "2026-10-18T10:01:00");
    }

    @Test
    void ownDraftOnAChildPageLinksTheParentPage() {
        clock.set(NOW);
        PostId parent = PostFixtures.builder(site)
                .type("page")
                .author(bob)
                .title("parent page")
                .insert(data);
        PostId child = PostFixtures.builder(site)
                .type("page")
                .status("draft")
                .author(bob)
                .slug("")
                .title("child page")
                .parent(parent)
                .publishedAt(null)
                .insert(data);
        String pages = "/wp-json/wp/v2/pages/";
        Response own = as(bob).contentType("application/json")
                .body("{\"content\":\"bob's own draft\"}")
                .post(pages + child.value() + "/autosaves");
        own.then().statusCode(200);
        JsonNode body = json(own);
        assertThat(body.get("id").asLong()).isEqualTo(child.value());
        assertThat(body.get("parent").asLong()).isEqualTo(parent.value());
        assertThat(body.get("date").isNull()).isTrue();
        assertThat(body.get("date_gmt").isNull()).isTrue();
        assertThat(body.get("slug").asText()).isEmpty();
        assertThat(body.get("preview_link").asText()).isEqualTo(home() + "?page_id=" + child.value() + "&preview=true");
        assertThat(body.get("_links").toString())
                .isEqualTo("{\"parent\":[{\"href\":\"http://" + host + pages + parent.value() + "\"}]}");
        as(bob).get(pages + child.value() + "?context=edit")
                .then()
                .statusCode(200)
                .body("content.raw", equalTo("bob's own draft"));

        Response alices = as(alice)
                .contentType("application/json")
                .body("{\"content\":\"alice's autosave\"}")
                .post(pages + child.value() + "/autosaves");
        alices.then().statusCode(200);
        JsonNode autosave = json(alices);
        assertThat(autosave.get("parent").asLong()).isEqualTo(child.value());
        assertThat(autosave.get("slug").asText()).isEqualTo(child.value() + "-autosave-v1");
        String token = token(autosave.get("preview_link").asText());
        assertThat(token).hasSize(27);
        assertThat(autosave.get("preview_link").asText())
                .isEqualTo(home() + "?page_id=" + child.value() + "&preview_id=" + child.value() + "&preview_nonce="
                        + token + "&preview=true");
        assertThat(autosave.at("/_links/parent/0/href").asText()).isEqualTo("http://" + host + pages + child.value());
    }

    // ---- helpers

    /** A request to the test site as the user with this id, or anonymous when null. */
    private static RequestSpecification as(Long user) {
        return Http.at(host, user == null ? null : "test-" + user);
    }

    private static Response autosave(Long user, PostId post, String body) {
        return as(user).contentType("application/json").body(body).post(list(post));
    }

    private static String list(PostId post) {
        return "/wp-json/wp/v2/posts/" + post.value() + "/autosaves";
    }

    private static String home() {
        return "http://" + host + "/";
    }

    /** The {@code preview_nonce} of a preview link. */
    private static String token(String link) {
        int start = link.indexOf("preview_nonce=") + "preview_nonce=".length();
        return link.substring(start, link.indexOf('&', start));
    }

    private static long tokenRows() {
        return Jdbc.count(data, "select count(*) from qp_post_token where site_id = ?", site.value());
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

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The post's stored slug, read past the services. */
    private static String slug(PostId post) {
        return Jdbc.strings(data, "select slug from qp_post where id = ?", post.value())
                .get(0);
    }

    /** The post's stored content, read past the services. */
    private static String content(PostId post) {
        return Jdbc.strings(data, "select content from qp_post where id = ?", post.value())
                .get(0);
    }

    /** An autosave row of {@code author} on the post, written past the services; answers its id. */
    private static long autosaveRow(PostId post, long author, String content, String createdAt) {
        String marker = "auto-" + UUID.randomUUID();
        Jdbc.update(
                data,
                "insert into qp_revision (site_id, post_id, author_id, title, content, excerpt, meta, autosave,"
                        + " autosave_owner, created_at) values (?, ?, ?, ?, ?, ?, " + TestDb.json() + ", ?, ?, "
                        + PostFixtures.instant() + ")",
                site.value(),
                post.value(),
                author,
                "",
                content,
                marker,
                "{}",
                true,
                author,
                PostFixtures.instantText(createdAt));
        long id = Jdbc.count(data, "select id from qp_revision where excerpt = ?", marker);
        Jdbc.update(data, "update qp_revision set excerpt = '' where id = ?", id);
        return id;
    }

    private static List<String> dates(JsonNode body) {
        return List.of(
                body.get("date").asText(),
                body.get("date_gmt").asText(),
                body.get("modified").asText(),
                body.get("modified_gmt").asText());
    }

    private static String error(String code, String message, int status) {
        return "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":{\"status\":" + status + "}}";
    }

    private static List<Long> ids(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        list.forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode json(Response response) {
        try {
            return MAPPER.readTree(response.asString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
