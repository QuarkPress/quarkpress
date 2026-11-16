package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.quarkpress.posts.PostType;

/**
 * The editor route's {@code preload} map, {@code GET /quarkpress/v1/editor/<type>/<id>}: the requests WordPress's edit
 * screen preloads that QuarkPress serves, keyed by WordPress's paths verbatim in WordPress's order (fact v08), the
 * single {@code OPTIONS} key where the first OPTIONS request falls, each entry the caller's own answer with only the
 * answer's headers, nothing but 200s, the settings read for administrators only, never the templates or global-styles
 * routes, and the author entry only for a type supporting authors. Members of the site: the super admin alice, the
 * site administrator sam, the editor bob (Bob Baker) and the contributor dave.
 */
class EditorPreloadDbTest {

    /** A type in REST without author support, so its posts preload no author. */
    @PostType(
            key = "note",
            name = "Notes",
            singularName = "Note",
            restBase = "notes",
            supports = {"title", "editor"})
    public static final class NoteType {
        private NoteType() {}
    }

    @RegisterExtension
    static final QuarkusExtensionTest app =
            RestApp.network(EditorPreloadDbTest.class, NoteType.class, PostFixtures.class, PostFixtures.Builder.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String EDITOR = "/wp-json/quarkpress/v1/editor/";

    private static final String INDEX_FIELDS = "description,gmt_offset,home,image_max_bit_depth,image_sizes,"
            + "image_size_threshold,image_strip_meta,name,site_icon,site_icon_url,site_logo,timezone_string,url,"
            + "page_for_posts,page_on_front,show_on_front";

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long alice;
    static long sam;
    static long bob;
    static long dave;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.site(data);
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        alice = member("alice", "Alice Adams", "alice@example.com", "administrator");
        TestData.superAdmin(data, UserId.of(alice));
        sam = member("sam", "Sam Site", "sam@example.com", "administrator");
        bob = member("bob", "Bob Baker", "bob@example.com", "editor");
        dave = member("dave", "Dave Diaz", "dave@example.com", "contributor");
    }

    private static long member(String nicename, String displayName, String email, String role) {
        UserId user = TestData.profile(data, nicename, displayName, email);
        TestData.member(data, site, user, "[\"" + role + "\"]");
        return user.value();
    }

    /** Fact v08: the served keys for bob on post {@code id}, in WordPress's order, with the author entry (bob). */
    private static List<String> keysForBob(PostId post) {
        return List.of(
                "/wp/v2/types?context=view",
                "/wp/v2/taxonomies?context=view",
                "/wp/v2/posts/" + post.value() + "?context=edit",
                "/wp/v2/types/post?context=edit",
                "/wp/v2/users/me",
                "OPTIONS",
                "/wp/v2/posts/" + post.value() + "/autosaves?context=edit",
                "/wp/v2/themes?context=edit&status=active",
                "/wp/v2/block-patterns/categories",
                "/?_fields=" + INDEX_FIELDS,
                "/wp/v2/taxonomies?context=edit",
                "/wp/v2/users/" + bob + "?context=view&_fields=id,name");
    }

    @Test
    void keysAndOrderForBob() {
        PostId post = publishedOf(bob);
        Response answer = as(bob).get(EDITOR + "post/" + post.value());
        answer.then().statusCode(200).header("Allow", "GET");
        JsonNode body = json(answer);
        assertThat(names(body)).containsExactly("settings", "preload", "blockDefinitions", "dateSettings");
        JsonNode preload = body.get("preload");
        assertThat(names(preload)).containsExactlyElementsOf(keysForBob(post));
        // the OPTIONS key right after /wp/v2/users/me holds the OPTIONS answers, the posts route last
        assertThat(names(preload.get("OPTIONS")))
                .containsExactly("/wp/v2/media", "/wp/v2/pages", "/wp/v2/blocks", "/wp/v2/settings", "/wp/v2/posts");
        // every entry is {body, headers}
        for (String key : names(preload)) {
            if (key.equals("OPTIONS")) {
                for (String route : names(preload.get("OPTIONS"))) {
                    assertThat(names(preload.get("OPTIONS").get(route)))
                            .as(route)
                            .containsExactly("body", "headers");
                }
            } else {
                assertThat(names(preload.get(key))).as(key).containsExactly("body", "headers");
            }
        }
    }

    @Test
    void entriesAreTheCallersOwnAnswers() {
        PostId bobs = publishedOf(bob);
        PostId daves = draftOf(dave);
        for (long user : List.of(bob, dave)) {
            PostId post = user == bob ? bobs : daves;
            JsonNode preload = preload(user, "post", post);
            for (String key : names(preload)) {
                if (key.equals("OPTIONS")) {
                    for (String route : names(preload.get("OPTIONS"))) {
                        Response http = as(user).options("/wp-json" + route);
                        http.then().statusCode(200);
                        JsonNode entry = preload.get("OPTIONS").get(route);
                        assertThat(entry.get("body"))
                                .as("OPTIONS %s as %s", route, user)
                                .isEqualTo(json(http));
                        assertThat(entry.at("/headers/Allow").textValue())
                                .as("Allow of OPTIONS %s", route)
                                .isEqualTo(http.header("Allow"));
                    }
                    continue;
                }
                Response http = as(user).get("/wp-json" + key);
                http.then().statusCode(200);
                JsonNode entry = preload.get(key);
                assertThat(entry.get("body")).as("%s as %s", key, user).isEqualTo(json(http));
                assertThat(entry.at("/headers/Allow").textValue())
                        .as("Allow of %s", key)
                        .isEqualTo(http.header("Allow"));
            }
        }
        // the post item's own headers: the alternate Link before Allow (fact v08); dave's entry is dave's draft
        JsonNode bobsPreload = preload(bob, "post", bobs);
        JsonNode item = bobsPreload.get("/wp/v2/posts/" + bobs.value() + "?context=edit");
        assertThat(names(item.get("headers"))).containsExactly("Link", "Allow");
        assertThat(item.at("/headers/Link").textValue())
                .isEqualTo(as(bob).get("/wp-json/wp/v2/posts/" + bobs.value() + "?context=edit")
                        .header("Link"));
        assertThat(item.at("/headers/Allow").textValue()).isEqualTo("GET, POST, PUT, PATCH, DELETE");
        assertThat(item.at("/body/content/raw").isTextual()).isTrue();
        JsonNode davesPreload = preload(dave, "post", daves);
        assertThat(davesPreload
                        .get("/wp/v2/posts/" + daves.value() + "?context=edit")
                        .at("/body/status")
                        .textValue())
                .isEqualTo("draft");
        assertThat(davesPreload.at("/~1wp~1v2~1users~1me/body/id").longValue()).isEqualTo(dave);
        assertThat(bobsPreload.at("/~1wp~1v2~1users~1me/body/id").longValue()).isEqualTo(bob);
        // the themes collection: the totals as JSON numbers, then Allow (fact v08)
        JsonNode themes = bobsPreload.get("/wp/v2/themes?context=edit&status=active");
        assertThat(names(themes.get("headers"))).containsExactly("X-WP-Total", "X-WP-TotalPages", "Allow");
        assertThat(themes.at("/headers/X-WP-Total").isIntegralNumber()).isTrue();
        assertThat(themes.at("/headers/X-WP-Total").intValue()).isEqualTo(1);
        assertThat(themes.at("/headers/X-WP-TotalPages").intValue()).isEqualTo(1);
        assertThat(themes.at("/headers/Allow").textValue()).isEqualTo("GET");
        assertThat(themes.at("/body/0/stylesheet").textValue()).isEqualTo("quarkpress");
        // elsewhere only Allow
        assertThat(names(bobsPreload.get("/wp/v2/types?context=view").get("headers")))
                .containsExactly("Allow");
        assertThat(names(bobsPreload.get("OPTIONS").get("/wp/v2/posts").get("headers")))
                .containsExactly("Allow");
        assertThat(bobsPreload
                        .get("OPTIONS")
                        .get("/wp/v2/posts")
                        .at("/headers/Allow")
                        .textValue())
                .isEqualTo("GET, POST");
        // the index entry carries the requested fields only, the autosaves none
        assertThat(names(bobsPreload.get("/?_fields=" + INDEX_FIELDS).get("body")))
                .contains("description", "name", "url", "home", "gmt_offset", "timezone_string")
                .doesNotContain("namespaces", "routes");
        assertThat(bobsPreload
                        .get("/wp/v2/posts/" + bobs.value() + "/autosaves?context=edit")
                        .get("body")
                        .toString())
                .isEqualTo("[]");
    }

    @Test
    void nonAdministratorsLoseSettings() {
        PostId bobs = publishedOf(bob);
        PostId daves = draftOf(dave);
        for (long user : List.of(bob, dave)) {
            JsonNode preload = preload(user, "post", user == bob ? bobs : daves);
            assertThat(names(preload)).as("%s", user).doesNotContain("/wp/v2/settings");
            // the OPTIONS entry stays, a 200 whose caller has no method: "headers": [] (facts v08, v09)
            JsonNode options = preload.get("OPTIONS").get("/wp/v2/settings");
            assertThat(options).as("%s", user).isNotNull();
            assertThat(options.get("headers").toString()).isEqualTo("[]");
            assertThat(options.at("/body/namespace").textValue()).isEqualTo("wp/v2");
        }
        for (long administrator : List.of(alice, sam)) {
            PostId post = publishedOf(administrator);
            JsonNode preload = preload(administrator, "post", post);
            List<String> keys = names(preload);
            assertThat(keys).as("%s", administrator).contains("/wp/v2/settings");
            // right after the autosaves, before the themes (fact v08)
            assertThat(keys.indexOf("/wp/v2/settings"))
                    .isEqualTo(keys.indexOf("/wp/v2/posts/" + post.value() + "/autosaves?context=edit") + 1);
            assertThat(keys.get(keys.indexOf("/wp/v2/settings") + 1))
                    .isEqualTo("/wp/v2/themes?context=edit&status=active");
            JsonNode settings = preload.get("/wp/v2/settings");
            assertThat(settings.at("/headers/Allow").textValue()).isEqualTo("GET, POST, PUT, PATCH");
            assertThat(settings.at("/body/title").isTextual()).isTrue();
            assertThat(preload.get("OPTIONS")
                            .get("/wp/v2/settings")
                            .at("/headers/Allow")
                            .textValue())
                    .isEqualTo("GET, POST, PUT, PATCH");
        }
    }

    @Test
    void unservedRoutesAreAbsent() {
        PostId post = publishedOf(bob);
        JsonNode preload = preload(bob, "post", post);
        List<String> keys = new ArrayList<>(names(preload));
        keys.addAll(names(preload.get("OPTIONS")));
        // a map that was built: the first entries and the OPTIONS routes are there
        assertThat(keys).contains("/wp/v2/types?context=view", "/wp/v2/users/me", "/wp/v2/media", "/wp/v2/posts");
        assertThat(keys).noneMatch(key -> key.startsWith("/wp/v2/templates") || key.startsWith("/wp/v2/global-styles"));
        assertThat(keys).doesNotContain("/wp/v2/templates", "/wp/v2/templates/lookup?slug=front-page");
    }

    @Test
    void authorEntryOnlyForAuthorTypes() {
        PostId page = PostFixtures.builder(site)
                .type("page")
                .author(bob)
                .title("Page " + UUID.randomUUID())
                .insert(data);
        JsonNode preload = preload(bob, "page", page);
        String author = "/wp/v2/users/" + bob + "?context=view&_fields=id,name";
        List<String> keys = names(preload);
        assertThat(keys).endsWith("/wp/v2/taxonomies?context=edit", author);
        assertThat(preload.get(author).get("body").toString()).isEqualTo("{\"id\":" + bob + ",\"name\":\"Bob Baker\"}");
        assertThat(preload.get(author).at("/headers/Allow").textValue()).isEqualTo("GET, POST, PUT, PATCH");
        assertThat(keys)
                .contains("/wp/v2/pages/" + page.value() + "?context=edit", "/wp/v2/types/page?context=edit")
                .doesNotContain("/wp/v2/posts/" + page.value() + "?context=edit");
        assertThat(names(preload.get("OPTIONS")))
                .containsExactly("/wp/v2/media", "/wp/v2/pages", "/wp/v2/blocks", "/wp/v2/settings");
        // a type without author support preloads no author (its posts route is the OPTIONS base)
        PostId note = PostFixtures.builder(site)
                .type("note")
                .author(bob)
                .title("Note " + UUID.randomUUID())
                .insert(data);
        JsonNode notes = preload(bob, "note", note);
        assertThat(names(notes))
                .noneMatch(key -> key.startsWith("/wp/v2/users/" + bob))
                .endsWith("/wp/v2/taxonomies?context=edit")
                .contains("/wp/v2/notes/" + note.value() + "?context=edit", "/wp/v2/types/note?context=edit");
        assertThat(names(notes.get("OPTIONS")))
                .containsExactly("/wp/v2/media", "/wp/v2/pages", "/wp/v2/blocks", "/wp/v2/settings", "/wp/v2/notes");
        // the autosaves entry is preloaded whatever the type supports (the client then never asks)
        assertThat(names(notes)).contains("/wp/v2/notes/" + note.value() + "/autosaves?context=edit");
    }

    // ---- helpers

    /** A request to the test site as the user with this id, or anonymous when null. */
    private static RequestSpecification as(Long user) {
        return Http.at(host, user == null ? null : "test-" + user);
    }

    private static JsonNode preload(long user, String type, PostId post) {
        Response answer = as(user).get(EDITOR + type + "/" + post.value());
        answer.then().statusCode(200);
        return json(answer).get("preload");
    }

    private static PostId publishedOf(long author) {
        return PostFixtures.builder(site)
                .author(author)
                .title("Edited " + UUID.randomUUID())
                .insert(data);
    }

    private static PostId draftOf(long author) {
        return PostFixtures.builder(site)
                .status("draft")
                .author(author)
                .title("Drafted " + UUID.randomUUID())
                .publishedAt(null)
                .insert(data);
    }

    private static List<String> names(JsonNode node) {
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
