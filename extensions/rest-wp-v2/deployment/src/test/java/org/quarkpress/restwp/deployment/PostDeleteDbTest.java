package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.specification.RequestSpecification;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * {@code DELETE /wp/v2/<base>/<id>}: the lookup and {@code delete_post} check, the trash (answered in the edit context
 * with links, 410 once trashed) and {@code force} (the post deleted for good, answered as {@code deleted} and the
 * edit-context post without links as {@code previous}). Members: the administrator, the editor bob, the author carol,
 * the contributor dave.
 */
class PostDeleteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            RestApp.network(PostDeleteDbTest.class, PostFixtures.class, PostFixtures.Builder.class);

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long administrator;
    static long bob;
    static long carol;
    static long dave;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.site(data);
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        administrator = member("administrator");
        bob = member("editor");
        carol = member("author");
        dave = member("contributor");
    }

    private static long member(String role) {
        UserId user = TestData.profile(data, role);
        TestData.member(data, site, user, "[\"" + role + "\"]");
        return user.value();
    }

    static RequestSpecification as(Long user) {
        return Http.at(host, user == null ? null : "test-" + user);
    }

    private static PostId published(long author, String slug) {
        return PostFixtures.builder(site)
                .status("publish")
                .author(author)
                .title("hello")
                .slug(slug)
                .content("words")
                .publishedAt("2026-09-01T10:00:00Z")
                .modifiedAt("2026-09-01T10:00:00Z")
                .insert(data);
    }

    private static long rows(PostId post) {
        return Jdbc.count(data, "select count(*) from qp_post where id = ?", post.value());
    }

    @Test
    void trashThenForce() {
        PostId carols = published(carol, "hello");
        String item = "/wp-json/wp/v2/posts/" + carols.value();
        // the trashed post, in the edit context whatever was asked for, with its links; the trash saved a revision
        as(carol)
                .delete(item + "?context=view")
                .then()
                .statusCode(200)
                .header("Allow", "GET, POST, PUT, PATCH, DELETE")
                .header("Link", endsWith("/wp-json/>; rel=\"https://api.w.org/\""))
                .body("id", equalTo((int) carols.value()))
                .body("status", equalTo("trash"))
                .body("slug", equalTo("hello__trashed"))
                .body("title.raw", equalTo("hello"))
                .body("content.raw", equalTo("words"))
                .body("password", equalTo(""))
                .body("link", equalTo("http://" + host + "/?p=" + carols.value()))
                .body("permalink_template", equalTo("http://" + host + "/?p=" + carols.value()))
                .body("_links", hasKey("self"))
                .body("_links", hasKey("predecessor-version"));
        assertThat(Jdbc.strings(data, "select status from qp_post where id = ?", carols.value()))
                .containsExactly("trash");
        // a second trash is refused, with the item's full Allow
        as(carol)
                .delete(item)
                .then()
                .statusCode(410)
                .header("Allow", "GET, POST, PUT, PATCH, DELETE")
                .body("code", equalTo("rest_already_trashed"))
                .body("message", equalTo("The post has already been deleted."))
                .body("data.status", equalTo(410));
        // force deletes it for good: previous is the edit-context post without links; nothing is left to allow
        as(carol)
                .delete(item + "?force=true")
                .then()
                .statusCode(200)
                .header("Allow", nullValue())
                .body("deleted", equalTo(true))
                .body("previous.id", equalTo((int) carols.value()))
                .body("previous.status", equalTo("trash"))
                .body("previous.title.raw", equalTo("hello"))
                .body("previous", hasKey("permalink_template"))
                .body("previous", not(hasKey("_links")))
                .body("$", not(hasKey("_links")));
        assertThat(rows(carols)).isZero();
        as(carol)
                .get(item)
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_id"))
                .body("message", equalTo("Invalid post ID."));
    }

    @Test
    void forceFalseTrashesAndForceDeletesALivePost() {
        PostId post = published(bob, "hello-" + UUID.randomUUID());
        String item = "/wp-json/wp/v2/posts/" + post.value();
        as(bob).delete(item + "?force=false").then().statusCode(200).body("status", equalTo("trash"));
        PostId live = published(bob, "live-" + UUID.randomUUID());
        as(administrator)
                .delete("/wp-json/wp/v2/posts/" + live.value() + "?force=true")
                .then()
                .statusCode(200)
                .body("deleted", equalTo(true))
                .body("previous.status", equalTo("publish"));
        assertThat(rows(live)).isZero();
        // a page is trashed on its own route
        PostId page = PostFixtures.builder(site)
                .type("page")
                .status("publish")
                .author(bob)
                .title("a page")
                .slug("page-" + UUID.randomUUID())
                .insert(data);
        as(bob).delete("/wp-json/wp/v2/pages/" + page.value())
                .then()
                .statusCode(200)
                .body("type", equalTo("page"))
                .body("status", equalTo("trash"));
        // a post is not found under the pages route
        as(bob).delete("/wp-json/wp/v2/pages/" + post.value())
                .then()
                .statusCode(404)
                .body("code", equalTo("rest_post_invalid_id"));
    }

    @Test
    void refusals() {
        PostId carols = published(carol, "refused-" + UUID.randomUUID());
        String item = "/wp-json/wp/v2/posts/" + carols.value();
        as(dave).delete(item)
                .then()
                .statusCode(403)
                .header("Allow", "GET")
                .body("code", equalTo("rest_cannot_delete"))
                .body("message", equalTo("Sorry, you are not allowed to delete this post."))
                .body("data.status", equalTo(403));
        as(null).delete(item)
                .then()
                .statusCode(401)
                .header("Allow", "GET")
                .body("code", equalTo("rest_cannot_delete"))
                .body("message", equalTo("Sorry, you are not allowed to delete this post."))
                .body("data.status", equalTo(401));
        as(dave).delete(item + "?force=true").then().statusCode(403).body("code", equalTo("rest_cannot_delete"));
        assertThat(Jdbc.strings(data, "select status from qp_post where id = ?", carols.value()))
                .containsExactly("publish");
        // the permission comes before any trash check: a trashed post refuses dave with 403, not 410
        PostId trashed = PostFixtures.builder(site)
                .status("trash")
                .author(carol)
                .title("trashed")
                .insert(data);
        as(dave).delete("/wp-json/wp/v2/posts/" + trashed.value())
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_delete"));
        // a missing post and id 0, for any caller
        for (Long caller : new Long[] {null, dave, administrator}) {
            for (String id : new String[] {"999999999", "0"}) {
                as(caller)
                        .delete("/wp-json/wp/v2/posts/" + id)
                        .then()
                        .statusCode(404)
                        .header("Allow", nullValue())
                        .body("code", equalTo("rest_post_invalid_id"))
                        .body("message", equalTo("Invalid post ID."))
                        .body("data.status", equalTo(404));
            }
        }
    }

    @Test
    void anotherSitesPostIsNotFound() {
        SiteId other = TestData.site(data);
        PostId theirs = PostFixtures.builder(other)
                .status("publish")
                .author(administrator)
                .title("elsewhere")
                .insert(data);
        for (String query : new String[] {"", "?force=true"}) {
            as(administrator)
                    .delete("/wp-json/wp/v2/posts/" + theirs.value() + query)
                    .then()
                    .statusCode(404)
                    .header("Allow", nullValue())
                    .body("code", equalTo("rest_post_invalid_id"))
                    .body("message", equalTo("Invalid post ID."));
        }
        assertThat(Jdbc.strings(data, "select status from qp_post where id = ?", theirs.value()))
                .containsExactly("publish");
    }

    @Test
    void anEditorTrashesAProtectedPost() {
        PostId post = PostFixtures.builder(site)
                .status("publish")
                .author(carol)
                .title("guarded")
                .slug("guarded-" + UUID.randomUUID())
                .content("guarded words")
                .excerpt("guarded excerpt")
                .password("s3")
                .publishedAt("2026-09-01T10:00:00Z")
                .modifiedAt("2026-09-01T10:00:00Z")
                .insert(data);
        // the edit context of a caller who may edit the post lifts the protection; the flags still say it is there
        as(bob).delete("/wp-json/wp/v2/posts/" + post.value())
                .then()
                .statusCode(200)
                .body("status", equalTo("trash"))
                .body("password", equalTo("s3"))
                .body("content.raw", equalTo("guarded words"))
                .body("content.rendered", equalTo("guarded words"))
                .body("content.protected", equalTo(true))
                .body("excerpt.rendered", equalTo("guarded excerpt"))
                .body("excerpt.protected", equalTo(true))
                .body("class_list", hasItem("post-password-required"));
    }

    @Test
    void previousIsTheEditContextPost() {
        PostId post = PostFixtures.builder(site)
                .status("draft")
                .author(carol)
                .title("hello")
                .slug("")
                .content("secret words")
                .password("s3")
                .publishedAt(null)
                .modifiedAt("2026-09-01T10:00:00Z")
                .insert(data);
        as(carol)
                .delete("/wp-json/wp/v2/posts/" + post.value() + "?force=true&context=embed")
                .then()
                .statusCode(200)
                .body("deleted", equalTo(true))
                .body("previous.id", equalTo((int) post.value()))
                .body("previous.status", equalTo("draft"))
                .body("previous.title.raw", equalTo("hello"))
                .body("previous.content.raw", equalTo("secret words"))
                .body("previous.content.rendered", equalTo("secret words"))
                .body("previous.content.protected", equalTo(true))
                .body("previous.password", equalTo("s3"))
                .body("previous.generated_slug", equalTo("hello"))
                .body("previous.permalink_template", equalTo("http://" + host + "/?p=" + post.value()))
                .body("previous", not(hasKey("_links")));
        assertThat(rows(post)).isZero();
    }
}
