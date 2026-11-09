package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * An application with the REST module and its required modules only: the optional revisions, scheduling, multisite
 * and media modules (runtime and deployment artifacts, the media storage and image processor included) are left out
 * of the build (the installed features name none of them). It boots; the index lists no revisions, autosaves or media
 * route; a post links no version history and no predecessor, since no revisions route exists to link to; the posts
 * write routes work; and the preview page shows a draft to a session holder and a published post as stored, with no
 * autosave overlay. Members of the main site: the editor bob and the author carol.
 */
class WithoutOptionalModulesDbTest {

    private static final Set<ArtifactKey> OPTIONAL_MODULES = Set.of(
            ArtifactKey.of("org.quarkpress", "quarkpress-revisions", "", "jar"),
            ArtifactKey.of("org.quarkpress", "quarkpress-revisions-deployment", "", "jar"),
            ArtifactKey.of("org.quarkpress", "quarkpress-scheduling", "", "jar"),
            ArtifactKey.of("org.quarkpress", "quarkpress-scheduling-deployment", "", "jar"),
            ArtifactKey.of("org.quarkpress", "quarkpress-multisite", "", "jar"),
            ArtifactKey.of("org.quarkpress", "quarkpress-multisite-deployment", "", "jar"),
            ArtifactKey.of("org.quarkpress", "quarkpress-media", "", "jar"),
            ArtifactKey.of("org.quarkpress", "quarkpress-media-deployment", "", "jar"),
            ArtifactKey.of("org.quarkpress", "quarkpress-media-storage-fs", "", "jar"),
            ArtifactKey.of("org.quarkpress", "quarkpress-media-storage-fs-deployment", "", "jar"),
            ArtifactKey.of("org.quarkpress", "quarkpress-media-image-imageio", "", "jar"),
            ArtifactKey.of("org.quarkpress", "quarkpress-media-image-imageio-deployment", "", "jar"));

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .setExcludedDependencies(OPTIONAL_MODULES)
            .withApplicationRoot(jar -> jar.addClasses(
                    WithoutOptionalModulesDbTest.class,
                    TestDb.class,
                    TestBearer.class,
                    TestBearer.Request.class,
                    Http.class,
                    Jdbc.class,
                    TestData.class,
                    PostFixtures.class,
                    PostFixtures.Builder.class))
            .overrideConfigKey("quarkus.datasource.\"quarkpress\".db-kind", TestDb.kind())
            .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
            .overrideConfigKey("quarkus.keycloak.devservices.enabled", "false")
            .overrideConfigKey("quarkus.oidc.tenant-enabled", "false")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.http.test-ssl-port", "0");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String COOKIE = "quarkpress_preview";

    private static final String SESSION = "/wp-json/quarkpress/v1/preview/session";

    /** The default of {@code quarkpress.posts.preview-lifetime}, 12 hours, in seconds. */
    private static final int LIFETIME = 43200;

    static AgroalDataSource data;
    static final SiteId site = TestData.MAIN;
    static long bob;
    static long carol;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        bob = member("editor");
        carol = member("author");
    }

    private static long member(String role) {
        UserId user = TestData.profile(data, role);
        TestData.member(data, site, user, "[\"" + role + "\"]");
        return user.value();
    }

    @Test
    void theIndexListsNoRevisionsAutosavesOrMediaRoute() {
        JsonNode routes = json(Http.as(null)
                        .get("/wp-json/")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .get("routes");
        List<String> paths = new ArrayList<>();
        routes.fieldNames().forEachRemaining(paths::add);
        assertThat(paths).contains("/wp/v2/posts", "/wp/v2/posts/(?P<id>[\\d]+)", "/wp/v2/pages");
        assertThat(paths).noneMatch(path -> path.contains("revisions") || path.contains("autosaves"));
        assertThat(paths).noneMatch(path -> path.startsWith("/wp/v2/media"));
        Http.as(null).get("/wp-json/wp/v2/posts/1/revisions").then().statusCode(404);
        Http.as(null).get("/wp-json/wp/v2/media").then().statusCode(404);
    }

    @Test
    void aPostLinksNoRevision() {
        PostId post = PostFixtures.builder(site)
                .author(carol)
                .title("Linked " + UUID.randomUUID())
                .insert(data);
        JsonNode links = json(Http.as(null)
                        .get("/wp-json/wp/v2/posts/" + post.value())
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .get("_links");
        List<String> relations = new ArrayList<>();
        links.fieldNames().forEachRemaining(relations::add);
        assertThat(relations)
                .containsExactly(
                        "self", "collection", "about", "author", "replies", "wp:attachment", "wp:term", "curies");
    }

    @Test
    void thePostsWriteRoutesWork() {
        String title = "Written " + UUID.randomUUID();
        Response created = as(carol).body("{\"title\":\"" + title + "\"}").post("/wp-json/wp/v2/posts");
        created.then().statusCode(201).body("status", equalTo("draft")).body("title.raw", equalTo(title));
        int id = created.path("id");
        as(carol)
                .body("{\"content\":\"Updated text\",\"status\":\"publish\"}")
                .put("/wp-json/wp/v2/posts/" + id)
                .then()
                .statusCode(200)
                .body("status", equalTo("publish"))
                .body("content.raw", equalTo("Updated text"));
        as(carol).delete("/wp-json/wp/v2/posts/" + id).then().statusCode(200).body("status", equalTo("trash"));
        as(bob).delete("/wp-json/wp/v2/posts/" + id + "?force=true")
                .then()
                .statusCode(200)
                .body("deleted", equalTo(true));
        assertThat(Jdbc.count(data, "select count(*) from qp_post where id = ?", id))
                .isZero();
    }

    @Test
    void thePreviewPageShowsTheStoredPost() {
        String session = session(carol);
        PostId draft = PostFixtures.builder(site)
                .status("draft")
                .author(carol)
                .title("Draft without revisions")
                .content("draft text")
                .publishedAt(null)
                .insert(data);
        page(session)
                .get("/?p=" + draft.value() + "&preview=true")
                .then()
                .statusCode(200)
                .body(allOf(
                        containsString("<h1 class=\"entry-title\">Draft without revisions</h1>"),
                        containsString("draft text")));
        page(null).get("/?p=" + draft.value() + "&preview=true").then().statusCode(404);
        // the editor's preview pair on a published post: the stored post, since no autosave can exist
        PostId post = PostFixtures.builder(site)
                .author(carol)
                .title("Published without revisions")
                .content("stored text")
                .insert(data);
        String token = token(post, carol, Instant.now());
        page(session)
                .get("/?p=" + post.value() + "&preview_id=" + post.value() + "&preview_nonce=" + token
                        + "&preview=true")
                .then()
                .statusCode(200)
                .body(allOf(
                        containsString("<h1 class=\"entry-title\">Published without revisions</h1>"),
                        containsString("stored text")));
        page(null)
                .get("/?p=" + post.value() + "&preview_id=" + post.value() + "&preview_nonce=" + token
                        + "&preview=true")
                .then()
                .statusCode(403)
                .body(not(containsString("stored text")));
    }

    // ---- helpers

    private static RequestSpecification as(long user) {
        return Http.as("test-" + user).contentType("application/json");
    }

    private static RequestSpecification page(String cookie) {
        RequestSpecification spec = Http.as(null);
        return cookie == null ? spec : spec.cookie(COOKIE, cookie);
    }

    private static String session(long user) {
        Response opened = Http.as("test-" + user).post(SESSION);
        opened.then().statusCode(200);
        return opened.getCookie(COOKIE);
    }

    /**
     * The preview token of {@code post} for {@code user} at {@code now}, computed as the posts module computes it
     * (base64url without padding of the first 20 bytes of HMAC-SHA256 over {@code preview|site|post|user|tick} with
     * the site's secret, the tick being the time in 6 hour steps); the secret is created when the site has none.
     */
    private static String token(PostId post, long user, Instant now) {
        String stored = Jdbc.optionJson(site, "quarkpress_preview_secret");
        if (stored == null) {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            stored = "\"" + Base64.getEncoder().encodeToString(secret) + "\"";
            Jdbc.option(site, "quarkpress_preview_secret", stored);
        }
        byte[] secret = Base64.getDecoder().decode(stored.substring(1, stored.length() - 1));
        long tick = Math.floorDiv(now.getEpochSecond(), LIFETIME / 2);
        String message = "preview|" + site.value() + "|" + post.value() + "|" + user + "|" + tick;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] full = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[20];
            System.arraycopy(full, 0, truncated, 0, 20);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(truncated);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode json(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
