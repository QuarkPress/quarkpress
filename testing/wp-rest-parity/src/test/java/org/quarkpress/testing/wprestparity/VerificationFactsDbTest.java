package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wpscenario.ScenarioDatabase;
import org.quarkpress.testing.wpscenario.WordPressTree;

/**
 * Records what WordPress answers for every item of the behaviour reference's verification list (items 1-28 of the REST
 * verification list, plus {@code site-410}), asserting only the six items the reference states firmly. Every item
 * creates the fixtures it needs through WordPress's REST API, deletes only what it created and restores any site state
 * it changes.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
@TestMethodOrder(MethodOrderer.MethodName.class)
class VerificationFactsDbTest {
    static ScenarioDatabase database;
    static WordPressRestSite site;
    static RestClient client;

    /** The verification list item the running test records under; null when it records nothing. */
    String item;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        database = ScenarioDatabase.start();
        site = WordPressRestSite.start(
                WordPressTree.ensure(System.getProperty("wp.version")), database, System.getProperty("wp.php", "php"));
        client = new RestClient(site.root(), RestClient.applicationPasswords(site));
    }

    @AfterAll
    static void stop() {
        try {
            VerificationFacts.save();
        } finally {
            if (site != null) {
                site.close();
            }
            if (database != null) {
                database.close();
            }
        }
    }

    @BeforeEach
    void name(TestInfo info) {
        String method = info.getTestMethod().orElseThrow().getName();
        // The OPTIONS bodies are saved on their own; their fixture exchanges are not facts.
        item = method.startsWith("site410") ? "site-410" : method.startsWith("d") ? method.substring(0, 3) : null;
    }

    // ---- verification list items
    // -------------------------------------------------------------------------------------------

    @Test
    void d01PlainModeHasNoWpJsonUrls() throws Exception {
        try {
            site.option("permalink_structure", TextNode.valueOf(""));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/posts"));
            send(RestCall.get("anonymous", "/wp-json/"));
            send(RestCall.get("anonymous", "/index.php?rest_route=/wp/v2/types/post"));
            send(RestCall.get("anonymous", "/?rest_route=/"));
        } finally {
            site.option("permalink_structure", TextNode.valueOf("/%postname%/"));
        }
        Exchange restored = send(RestCall.get("anonymous", "/wp-json/wp/v2"));
        assertThat(restored.status()).as("pretty mode restored").isEqualTo(200);
    }

    @Test
    void d02NonStringRestRoute() throws Exception {
        send(RestCall.get("anonymous", "/?rest_route[]=x"));
        send(RestCall.get("anonymous", "/wp-json/wp/v2?rest_route[]=x"));
        send(withHeaders(RestCall.get("anonymous", "/?rest_route[]=x"), Map.of("Accept", "application/json")));
    }

    @Test
    void d03QueryIdAgainstPathId() throws Exception {
        long other = createTerm("categories", named("d03-other"));
        try {
            send(RestCall.get("alice", "/wp-json/wp/v2/users/" + site.userId("alice") + "?id=" + site.userId("carol")));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/categories/1?id=" + other));
        } finally {
            deleteTerm("categories", other);
        }
    }

    @Test
    void d04MethodOverride() throws Exception {
        long tag = createTerm("tags", named("d04-tag"));
        boolean deleted = false;
        try {
            send(withHeaders(
                    RestCall.json("anonymous", "POST", "/wp-json/wp/v2/categories", "{}"),
                    Map.of("X-HTTP-Method-Override", "OPTIONS")));
            send(withHeaders(
                    RestCall.json("anonymous", "POST", "/wp-json/wp/v2/tags/" + tag + "?force=true", "{}"),
                    Map.of("X-HTTP-Method-Override", "DELETE")));
            send(RestCall.json("anonymous", "POST", "/wp-json/wp/v2/tags/" + tag + "?_method=DELETE&force=true", "{}"));
            Exchange delete = send(RestCall.get("alice", "/wp-json/wp/v2/tags/" + tag + "?_method=DELETE&force=true"));
            deleted = delete.status() == 200;
            send(RestCall.get("alice", "/wp-json/wp/v2/tags/" + tag));
        } finally {
            if (!deleted) {
                deleteTerm("tags", tag);
            }
        }
    }

    @Test
    void d05UnsupportedMethodAndValidationBeforePermission() throws Exception {
        Exchange delete = send(RestCall.json("alice", "DELETE", "/wp-json/wp/v2/types", "{}"));
        assertThat(delete.status()).isEqualTo(404);
        assertThat(delete.json().get("code").asText()).isEqualTo("rest_no_route");
        Exchange zero = send(RestCall.get("anonymous", "/wp-json/wp/v2/users?per_page=0"));
        assertThat(zero.status()).isEqualTo(400);
        assertThat(zero.json().get("code").asText()).isEqualTo("rest_invalid_param");
    }

    @Test
    void d06ApplicationPasswordFailures() throws Exception {
        send(RestCall.get("invalid", "/wp-json/wp/v2/users/me"));
        send(RestCall.get("invalid", "/wp-json/wp/v2/types"));
        send(RestCall.get("invalid", "/wp-json/qp/none"));
        send(withHeaders(
                RestCall.get("anonymous", "/wp-json/wp/v2/users/me"),
                Map.of("Authorization", RestClient.basic("nobody", "not-a-password"))));
        ObjectNode erin = OracleJson.MAPPER.createObjectNode().put("user", "erin");
        try {
            site.operation("revoke_passwords", erin);
            send(RestCall.get("erin", "/wp-json/wp/v2/users/me"));
        } finally {
            site.renewApplicationPassword("erin");
        }
        Exchange renewed = send(RestCall.get("erin", "/wp-json/wp/v2/users/me"));
        assertThat(renewed.status()).as("erin's renewed password").isEqualTo(200);
    }

    @Test
    void d07TermExistsOverHttp() throws Exception {
        Long tag = null;
        Long category = null;
        try {
            tag = createTerm("tags", named("d07-tag"));
            category = createTerm("categories", named("d07-category"));
            Exchange tagAgain = send(RestCall.json("alice", "POST", "/wp-json/wp/v2/tags", named("d07-tag")));
            Exchange categoryAgain =
                    send(RestCall.json("alice", "POST", "/wp-json/wp/v2/categories", named("d07-category")));
            note(
                    "tag term_id type",
                    tagAgain.json().at("/data/term_id").getNodeType().toString());
            note(
                    "category term_id type",
                    categoryAgain.json().at("/data/term_id").getNodeType().toString());
        } finally {
            deleteTermIfCreated("tags", tag);
            deleteTermIfCreated("categories", category);
        }
    }

    @Test
    void d08PhaseOneHidesPhaseTwo() throws Exception {
        JsonNode body = send(RestCall.get("anonymous", "/wp-json/wp/v2/categories?per_page=0&order=up"))
                .json();
        assertThat(body.at("/data/params").fieldNames()).toIterable().containsExactly("per_page");
        assertThat(body.at("/data/params/per_page").asText())
                .isEqualTo("per_page must be between 1 (inclusive) and 100 (inclusive)");
        assertThat(body.at("/data/details/per_page/code").asText()).isEqualTo("rest_out_of_bounds");

        Exchange shadowed = send(RestCall.json("alice", "POST", "/wp-json/wp/v2/tags?name[]=x", named("d08-ok")));
        if (shadowed.status() == 201) {
            deleteTerm("tags", shadowed.json().get("id").longValue());
        }
    }

    @Test
    void d09MalformedJson() throws Exception {
        send(rawBody("anonymous", "GET", "/wp-json/wp/v2/types", "application/json", "{bad"));
        send(rawBody("anonymous", "GET", "/wp-json/qp/none", "application/json", "{bad"));
        send(rawBody("anonymous", "GET", "/wp-json/wp/v2/types", "text/plain", "{bad"));
        send(rawBody("alice", "POST", "/wp-json/wp/v2/tags", "application/json", "{\"name\":"));
    }

    @Test
    void d10PaginationLinks() throws Exception {
        List<Long> tags = new ArrayList<>();
        try {
            for (int i = 1; i <= 25; i++) {
                tags.add(createTerm("tags", named("d10-tag-%02d".formatted(i))));
            }
            send(RestCall.get("anonymous", "/wp-json/wp/v2/tags?per_page=10&page=2&_fields=id"));
            send(RestCall.get(
                    "anonymous",
                    "/wp-json/wp/v2/tags?include[]=" + tags.get(0) + "&include[]=" + tags.get(1) + "&per_page=1"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/tags?_fields=id,name&per_page=10"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/tags?search=d10-none&page=2"));
            try {
                site.option("permalink_structure", TextNode.valueOf(""));
                send(RestCall.get("anonymous", "/?rest_route=/wp/v2/tags&per_page=10&page=2"));
                send(RestCall.get("anonymous", "/index.php?rest_route=/wp/v2/tags&per_page=10&page=2"));
            } finally {
                site.option("permalink_structure", TextNode.valueOf("/%postname%/"));
            }
        } finally {
            for (long tag : tags) {
                deleteTerm("tags", tag);
            }
        }
    }

    @Test
    void d11HeaderSets() throws Exception {
        send(RestCall.get("alice", "/wp-json/wp/v2/types/post"));
        send(RestCall.get("anonymous", "/wp-json/wp/v2/types"));
        send(withHeaders(RestCall.get("anonymous", "/wp-json/wp/v2/types"), Map.of("Origin", "https://x.test")));
        send(new RestCall(
                "anonymous",
                "OPTIONS",
                "/wp-json/wp/v2/categories",
                Map.of(
                        "Origin", "https://x.test",
                        "Access-Control-Request-Method", "POST",
                        "Access-Control-Request-Headers", "authorization,content-type"),
                Optional.empty(),
                Optional.empty()));
        send(RestCall.get("anonymous", "/wp-json/qp/none"));
    }

    @Test
    void d12OptionsOnAnUnknownPathIsAnEmptyList() throws Exception {
        Exchange options = send(
                new RestCall("anonymous", "OPTIONS", "/wp-json/qp/none", Map.of(), Optional.empty(), Optional.empty()));
        assertThat(options.status()).isEqualTo(200);
        assertThat(options.text()).isEqualTo("[]");
        assertThat(options.header("Allow")).isEmpty();
        send(new RestCall(
                "anonymous", "OPTIONS", "/wp-json/wp/v2/categories", Map.of(), Optional.empty(), Optional.empty()));
    }

    @Test
    void d13Head() throws Exception {
        long tag = createTerm("tags", named("d13-tag"));
        try {
            send(new RestCall(
                    "anonymous", "HEAD", "/wp-json/wp/v2/tags", Map.of(), Optional.empty(), Optional.empty()));
            send(new RestCall(
                    "alice", "HEAD", "/wp-json/wp/v2/settings", Map.of(), Optional.empty(), Optional.empty()));
        } finally {
            deleteTerm("tags", tag);
        }
    }

    @Test
    void d14JsonEscapes() throws Exception {
        String cafe = "Caf" + new String(Character.toChars(0xE9)) + "/Bar <b>";
        String withEmoji = cafe + " & " + new String(Character.toChars(0x1F600));
        Long first = null;
        Long second = null;
        String title = null;
        try {
            first = createTerm(
                    "tags",
                    OracleJson.MAPPER.createObjectNode().put("name", cafe).toString());
            second = createTerm(
                    "tags",
                    OracleJson.MAPPER.createObjectNode().put("name", withEmoji).toString());
            title = settings().get("title").textValue();
            send(RestCall.get("anonymous", "/wp-json/wp/v2/tags/" + first));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/tags/" + second));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/tags/" + first + "?_pretty"));
            send(RestCall.json("alice", "POST", "/wp-json/wp/v2/settings", "{\"title\":\"A & B\"}"));
            send(RestCall.get("alice", "/wp-json/wp/v2/settings?_fields=title"));
        } finally {
            if (title != null) {
                site.option("blogname", TextNode.valueOf(title));
            }
            deleteTermIfCreated("tags", first);
            deleteTermIfCreated("tags", second);
        }
    }

    @Test
    void d15EnvelopeAndJsonp() throws Exception {
        send(RestCall.get("anonymous", "/wp-json/qp/none?_envelope"));
        send(RestCall.get("anonymous", "/wp-json/wp/v2/categories/999999?_envelope"));
        send(RestCall.get("anonymous", "/wp-json/wp/v2/categories?_envelope"));
        send(RestCall.get("anonymous", "/wp-json/wp/v2/types?_jsonp=cb"));
        send(RestCall.get("anonymous", "/wp-json/wp/v2/types?_jsonp=a-b"));
    }

    @Test
    void d16FieldsQuirks() throws Exception {
        Long parent = null;
        Long child = null;
        try {
            parent = createTerm("categories", named("d16-parent"));
            child = createTerm("categories", "{\"name\":\"d16-child\",\"parent\":" + parent + "}");
            send(RestCall.get("anonymous", "/wp-json/wp/v2/types?_fields=name"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/categories/1?_fields=name,name.x,id"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/categories?_fields=id,_embedded&_embed"));
            send(RestCall.get(
                    "anonymous", "/wp-json/wp/v2/categories?_fields=id,_links,_embedded&_embed&parent=" + parent));
        } finally {
            deleteTermIfCreated("categories", child);
            deleteTermIfCreated("categories", parent);
        }
    }

    @Test
    void d17TargetHints() throws Exception {
        Long tag = null;
        Long post = null;
        try {
            tag = createTerm("tags", named("d17-tag"));
            post = createPost("carol", "publish", "d17 carol");
            for (String as : List.of("anonymous", "carol", "alice")) {
                send(RestCall.get(as, "/wp-json/wp/v2/categories/1"));
                send(RestCall.get(as, "/wp-json/wp/v2/tags/" + tag));
                send(RestCall.get(as, "/wp-json/wp/v2/users/" + site.userId("carol")));
            }
        } finally {
            deletePostIfCreated(post);
            deleteTermIfCreated("tags", tag);
        }
    }

    @Test
    void d18Embed() throws Exception {
        Long parent = null;
        Long child = null;
        try {
            parent = createTerm("categories", named("d18-parent"));
            child = createTerm("categories", "{\"name\":\"d18-child\",\"parent\":" + parent + "}");
            send(RestCall.get("anonymous", "/wp-json/wp/v2/categories/" + child + "?_embed=0"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/categories/" + child + "?_embed=up"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/categories/" + child + "?_embed=author"));
            note(
                    "embedded error",
                    "not recorded: a category's parent is readable by everyone in embed context, and deleting a parent"
                            + " re-parents its children, so a default install has no unreadable `up` link");
        } finally {
            deleteTermIfCreated("categories", child);
            deleteTermIfCreated("categories", parent);
        }
    }

    @Test
    void d19Index() throws Exception {
        send(RestCall.get("anonymous", "/wp-json/"));
        send(RestCall.get("bob", "/wp-json/"));
        send(RestCall.get("erin", "/wp-json/"));
        Exchange help = send(RestCall.get("anonymous", "/wp-json/?context=help"));
        note("help body bytes", help.body().length);
        send(RestCall.get("anonymous", "/wp-json/wp/v2?namespace=oembed/1.0"));
        send(RestCall.get("anonymous", "/wp-json/wp/v2?namespace=qp/none"));
    }

    @Test
    void d20Types() throws Exception {
        for (String type : List.of("post", "page", "attachment", "wp_block")) {
            send(RestCall.get("alice", "/wp-json/wp/v2/types/" + type + "?context=edit"));
        }
        send(RestCall.get("alice", "/wp-json/wp/v2/types?context=edit"));
        send(RestCall.get("erin", "/wp-json/wp/v2/types/revision"));
        send(RestCall.get("anonymous", "/wp-json/wp/v2/types/revision"));
        send(RestCall.get("erin", "/wp-json/wp/v2/types?context=edit"));
        send(RestCall.get("anonymous", "/wp-json/wp/v2/types/nope"));
    }

    @Test
    void d21Taxonomies() throws Exception {
        send(RestCall.get("anonymous", "/wp-json/wp/v2/taxonomies?type=page"));
        send(RestCall.get("anonymous", "/wp-json/wp/v2/taxonomies/post_format"));
        send(RestCall.get("anonymous", "/wp-json/wp/v2/taxonomies?context=edit"));
        send(RestCall.get("erin", "/wp-json/wp/v2/taxonomies?context=edit"));
        send(RestCall.get("anonymous", "/wp-json/wp/v2/taxonomies/nope"));
        send(RestCall.get("alice", "/wp-json/wp/v2/taxonomies/category?context=edit"));
    }

    @Test
    void d22InternalStatusesAreRefusedEvenToAdministrators() throws Exception {
        Exchange status = send(RestCall.get("alice", "/wp-json/wp/v2/statuses/auto-draft"));
        assertThat(status.status()).isEqualTo(403);
        assertThat(status.json().get("code").asText()).isEqualTo("rest_cannot_read_status");
        send(RestCall.get("anonymous", "/wp-json/wp/v2/statuses"));
        send(RestCall.get("alice", "/wp-json/wp/v2/statuses"));
        send(RestCall.get("anonymous", "/wp-json/wp/v2/statuses/nope"));
    }

    @Test
    void d23DefaultCategoryCannotBeDeleted() throws Exception {
        Exchange delete = send(RestCall.json("alice", "DELETE", "/wp-json/wp/v2/categories/1?force=true", "{}"));
        assertThat(delete.status()).isEqualTo(403);
        assertThat(delete.json().get("code").asText()).isEqualTo("rest_cannot_delete");

        Long tag = null;
        Long first = null;
        Long second = null;
        Long draft = null;
        List<Long> stray = new ArrayList<>();
        try {
            tag = createTerm("tags", named("d23-tag"));
            first = createTerm("categories", named("d23-first"));
            second = createTerm("categories", named("d23-second"));
            draft = createPost("dave", "draft", "d23 dave draft");
            Exchange crossParent = send(RestCall.json(
                    "alice", "POST", "/wp-json/wp/v2/categories", "{\"name\":\"d23-cross\",\"parent\":" + tag + "}"));
            strayTerm(crossParent, stray);
            Exchange empty =
                    send(RestCall.json("alice", "POST", "/wp-json/wp/v2/categories", "{\"name\":\"<b></b>\"}"));
            strayTerm(empty, stray);
            send(RestCall.json("alice", "POST", "/wp-json/wp/v2/categories/" + second, "{\"slug\":\"d23-first\"}"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/categories?post=" + draft));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/categories?post=999999"));
        } finally {
            for (long id : stray) {
                deleteTerm("categories", id);
            }
            deletePostIfCreated(draft);
            deleteTermIfCreated("categories", second);
            deleteTermIfCreated("categories", first);
            deleteTermIfCreated("tags", tag);
        }
    }

    @Test
    void d24Users() throws Exception {
        long carol = site.userId("carol");
        Long draft = null;
        Long privatePost = null;
        Long published = null;
        ObjectNode outsider = OracleJson.MAPPER.createObjectNode().put("login", "d24outsider");
        try {
            draft = createPost("dave", "draft", "d24 dave draft");
            long outsiderId = site.operation("outsider", outsider).get("id").longValue();
            send(RestCall.get("anonymous", "/wp-json/wp/v2/users"));
            send(RestCall.get("dave", "/wp-json/wp/v2/users?who=authors"));
            send(RestCall.get("erin", "/wp-json/wp/v2/users?who=authors"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/users/" + site.userId("erin")));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/users/" + outsiderId));
            send(RestCall.get("alice", "/wp-json/wp/v2/users/" + outsiderId));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/users/999999"));
            privatePost = createPost("carol", "private", "d24 carol private");
            send(RestCall.get("anonymous", "/wp-json/wp/v2/users/" + carol));
            send(RestCall.get("dave", "/wp-json/wp/v2/users/" + carol));
            send(RestCall.get("bob", "/wp-json/wp/v2/users/" + carol));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/users"));
            published = createPost("carol", "publish", "d24 carol published");
            send(RestCall.get("anonymous", "/wp-json/wp/v2/users"));
            send(RestCall.get("anonymous", "/wp-json/wp/v2/users/" + carol));
            // Searches once carol, and only carol, is visible to callers without list_users.
            send(RestCall.get("erin", "/wp-json/wp/v2/users?search=quarkpress"));
            send(RestCall.get("erin", "/wp-json/wp/v2/users?search=car"));
            send(RestCall.get("erin", "/wp-json/wp/v2/users?search=" + carol));
            send(RestCall.get("erin", "/wp-json/wp/v2/users?search=Chen"));
            send(RestCall.get("erin", "/wp-json/wp/v2/users?search=quarkpress&search_columns[]=email"));
            send(RestCall.get("erin", "/wp-json/wp/v2/users?search=quarkpress&who=authors"));
            send(RestCall.get("bob", "/wp-json/wp/v2/users?search=quarkpress"));
        } finally {
            deletePostIfCreated(published);
            deletePostIfCreated(privatePost);
            deletePostIfCreated(draft);
            // delete_user answers {"deleted": false} when the outsider was never created.
            site.operation("delete_user", outsider);
        }
    }

    @Test
    void d25UserFields() throws Exception {
        long erin = site.userId("erin");
        try {
            send(RestCall.get("erin", "/wp-json/wp/v2/users/me?context=edit"));
            send(RestCall.get("erin", "/wp-json/wp/v2/users/" + erin + "?context=edit"));
            send(RestCall.get("erin", "/wp-json/wp/v2/users/me"));
            send(RestCall.json(
                    "erin",
                    "PUT",
                    "/wp-json/wp/v2/users/me",
                    "{\"meta\":{\"persisted_preferences\":{\"core/edit-post\":{\"welcomeGuide\":false}}}}"));
            send(RestCall.get("erin", "/wp-json/wp/v2/users/me"));
            send(RestCall.get("erin", "/wp-json/wp/v2/users/me?context=edit"));
            send(RestCall.get("alice", "/wp-json/wp/v2/users/" + erin + "?context=edit"));
        } finally {
            send(RestCall.json(
                    "erin", "PUT", "/wp-json/wp/v2/users/me", "{\"meta\":{\"persisted_preferences\":null}}"));
        }
        send(RestCall.get("erin", "/wp-json/wp/v2/users/me?context=edit"));
    }

    @Test
    void d26UsersMeAnonymousIs401NotLoggedInWithAllowGet() throws Exception {
        Exchange me = send(RestCall.get("anonymous", "/wp-json/wp/v2/users/me"));
        assertThat(me.status()).isEqualTo(401);
        assertThat(me.json().get("code").asText()).isEqualTo("rest_not_logged_in");
        assertThat(me.header("Allow")).contains("GET");

        send(RestCall.json("anonymous", "PUT", "/wp-json/wp/v2/users/me", "{\"name\":\"x\"}"));
        try {
            send(RestCall.json(
                    "bob",
                    "PUT",
                    "/wp-json/wp/v2/users/me",
                    "{\"meta\":{\"persisted_preferences\":{\"core\":{\"fixedToolbar\":true},"
                            + "\"_modified\":\"2026-10-30T12:00:00.000Z\"}}}"));
            send(RestCall.get("bob", "/wp-json/wp/v2/users/me?context=edit"));
            send(RestCall.json(
                    "bob", "PUT", "/wp-json/wp/v2/users/me", "{\"meta\":{\"persisted_preferences\":\"x\"}}"));
            send(RestCall.get("bob", "/wp-json/wp/v2/users/me?context=edit"));
        } finally {
            send(RestCall.json("bob", "PUT", "/wp-json/wp/v2/users/me", "{\"meta\":{\"persisted_preferences\":null}}"));
        }
    }

    @Test
    void d27UserWritesOnMultisite() throws Exception {
        long bob = site.userId("bob");
        List<String> created = new ArrayList<>();
        try {
            Exchange create = send(RestCall.json(
                    "alice",
                    "POST",
                    "/wp-json/wp/v2/users",
                    "{\"username\":\"d27temp\",\"email\":\"d27temp@quarkpress.test\",\"password\":\"d27-Temp-pass\"}"));
            if (create.status() == 201) {
                created.add("d27temp");
                long id = create.json().get("id").longValue();
                send(RestCall.json(
                        "alice",
                        "DELETE",
                        "/wp-json/wp/v2/users/" + id + "?force=true&reassign=" + site.userId("alice"),
                        "{}"));
                send(RestCall.get("alice", "/wp-json/wp/v2/users/" + id));
            }
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/users/" + bob, "{\"roles\":[\"administrator\"]}"));
            Exchange asSiteAdmin = send(RestCall.json(
                    "bob",
                    "POST",
                    "/wp-json/wp/v2/users",
                    "{\"username\":\"d27bob\",\"email\":\"d27bob@quarkpress.test\",\"password\":\"d27-Bob-pass\"}"));
            if (asSiteAdmin.status() == 201) {
                created.add("d27bob");
            }
        } finally {
            send(RestCall.json("alice", "PUT", "/wp-json/wp/v2/users/" + bob, "{\"roles\":[\"editor\"]}"));
            for (String login : created) {
                site.operation(
                        "delete_user", OracleJson.MAPPER.createObjectNode().put("login", login));
            }
        }
    }

    @Test
    void d28Settings() throws Exception {
        JsonNode original =
                send(RestCall.get("alice", "/wp-json/wp/v2/settings")).json();
        Map<String, String> options = new LinkedHashMap<>();
        options.put("title", "blogname");
        options.put("timezone", "timezone_string");
        options.put("posts_per_page", "posts_per_page");
        options.put("date_format", "date_format");
        options.put("start_of_week", "start_of_week");
        options.put("default_ping_status", "default_ping_status");
        options.put("default_comment_status", "default_comment_status");
        options.put("language", "WPLANG");
        options.put("use_smilies", "use_smilies");
        try {
            setting("/wp-json/wp/v2/settings?title=X", "{}");
            setting("/wp-json/wp/v2/settings", "{\"title\":\"A & B <i>x</i>\"}");
            setting("/wp-json/wp/v2/settings", "{\"title\":null}");
            setting("/wp-json/wp/v2/settings", "{\"timezone\":\"Mars/Base\"}");
            setting("/wp-json/wp/v2/settings", "{\"posts_per_page\":\"abc\"}");
            setting("/wp-json/wp/v2/settings", "{\"default_comment_status\":\"maybe\"}");
            setting("/wp-json/wp/v2/settings", "{\"date_format\":\"<b>Y</b>-m-d\"}");
            setting("/wp-json/wp/v2/settings", "{\"start_of_week\":-3}");
            setting("/wp-json/wp/v2/settings", "{\"posts_per_page\":0}");
            setting("/wp-json/wp/v2/settings", "{\"posts_per_page\":-5}");
            setting("/wp-json/wp/v2/settings", "{\"default_ping_status\":\"\"}");
            setting("/wp-json/wp/v2/settings", "{\"language\":\"fr_FR\"}");
            setting("/wp-json/wp/v2/settings", "{\"email\":\"not-an-email\",\"url\":\"ftp://x.test\"}");
            setting("/wp-json/wp/v2/settings", "{\"qp_unknown\":1}");
            setting("/wp-json/wp/v2/settings", "{\"posts_per_page\":\"5\",\"title\":\"d28 & two\"}");
            // posts_per_page is sanitized on write ("abc" is stored as 1); use_smilies is stored as given.
            site.option("posts_per_page", TextNode.valueOf("abc"));
            send(RestCall.get("alice", "/wp-json/wp/v2/settings"));
            site.option("use_smilies", TextNode.valueOf("abc"));
            send(RestCall.get("alice", "/wp-json/wp/v2/settings"));
            setting("/wp-json/wp/v2/settings", "{\"title\":\"d28 before\",\"use_smilies\":null}");
            setting("/wp-json/wp/v2/settings", "{\"title\":\"d28 after\",\"posts_per_page\":null}");
            send(RestCall.get("bob", "/wp-json/wp/v2/settings"));
        } finally {
            for (Map.Entry<String, String> option : options.entrySet()) {
                site.option(option.getValue(), original.get(option.getKey()));
            }
        }
        JsonNode restored =
                send(RestCall.get("alice", "/wp-json/wp/v2/settings")).json();
        assertThat(restored).as("settings restored").isEqualTo(original);
    }

    // ---- after the items ----------------------------------------------------------------------------------------

    @Test
    void optionsOfEveryRouteServedIsSaved() throws Exception {
        long tag = createTerm("tags", named("options-tag"));
        try {
            List<String> routes = List.of(
                    "/",
                    "/wp/v2",
                    "/wp/v2/types",
                    "/wp/v2/types/post",
                    "/wp/v2/taxonomies",
                    "/wp/v2/taxonomies/category",
                    "/wp/v2/statuses",
                    "/wp/v2/statuses/publish",
                    "/wp/v2/categories",
                    "/wp/v2/categories/1",
                    "/wp/v2/tags",
                    "/wp/v2/tags/" + tag,
                    "/wp/v2/users",
                    "/wp/v2/users/1",
                    "/wp/v2/users/me",
                    "/wp/v2/settings");
            for (String route : routes) {
                Exchange options = client.send(new RestCall(
                        "alice", "OPTIONS", "/wp-json" + route, Map.of(), Optional.empty(), Optional.empty()));
                assertThat(options.status()).as("OPTIONS %s", route).isEqualTo(200);
                VerificationFacts.saveOptions(route.equals("/wp/v2/tags/" + tag) ? "/wp/v2/tags/{id}" : route, options);
            }
        } finally {
            deleteTerm("tags", tag);
        }
    }

    @Test
    void site410ArchivedSite() throws Exception {
        site.operation("archived_site", OracleJson.MAPPER.createObjectNode().put("path", "/gone/"));
        send(RestCall.get("anonymous", "/gone/wp-json/"));
        send(withHeaders(RestCall.get("anonymous", "/gone/wp-json/"), Map.of("Accept", "application/json, */*;q=0.1")));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Sends the call and records the exchange under the running item, if any. */
    Exchange send(RestCall call) throws Exception {
        Exchange exchange = client.send(call);
        if (item != null) {
            VerificationFacts.record(item, exchange);
        }
        return exchange;
    }

    void note(String key, String value) {
        VerificationFacts.note(item, key, TextNode.valueOf(value));
    }

    void note(String key, int value) {
        VerificationFacts.note(item, key, IntNode.valueOf(value));
    }

    static RestCall withHeaders(RestCall call, Map<String, String> headers) {
        Map<String, String> all = new LinkedHashMap<>(call.headers());
        all.putAll(headers);
        return new RestCall(call.as(), call.method(), call.target(), all, call.contentType(), call.body());
    }

    static RestCall rawBody(String as, String method, String target, String contentType, String body) {
        return new RestCall(
                as,
                method,
                target,
                Map.of(),
                Optional.of(contentType),
                Optional.of(body.getBytes(StandardCharsets.UTF_8)));
    }

    static String named(String name) {
        return OracleJson.MAPPER.createObjectNode().put("name", name).toString();
    }

    /** Creates a term as alice; the id of the new term. */
    long createTerm(String base, String json) throws Exception {
        Exchange created = send(RestCall.json("alice", "POST", "/wp-json/wp/v2/" + base, json));
        assertThat(created.status())
                .as("fixture %s %s: %s", base, json, created.text())
                .isEqualTo(201);
        return created.json().get("id").longValue();
    }

    void deleteTerm(String base, long id) throws Exception {
        send(RestCall.json("alice", "DELETE", "/wp-json/wp/v2/" + base + "/" + id + "?force=true", "{}"));
    }

    /** Deletes a fixture term when the item got as far as creating it. */
    void deleteTermIfCreated(String base, Long id) throws Exception {
        if (id != null) {
            deleteTerm(base, id);
        }
    }

    /** Deletes a fixture post when the item got as far as creating it. */
    void deletePostIfCreated(Long id) throws Exception {
        if (id != null) {
            deletePost(id);
        }
    }

    /** Remembers a term that a refusal-probing request created anyway, so the item deletes it. */
    static void strayTerm(Exchange exchange, List<Long> stray) {
        if (exchange.status() == 201) {
            stray.add(exchange.json().get("id").longValue());
        }
    }

    /** Creates a post as the user; the id of the new post. */
    long createPost(String as, String status, String title) throws Exception {
        String json = OracleJson.MAPPER
                .createObjectNode()
                .put("title", title)
                .put("status", status)
                .toString();
        Exchange created = send(RestCall.json(as, "POST", "/wp-json/wp/v2/posts", json));
        assertThat(created.status())
                .as("fixture post %s: %s", title, created.text())
                .isEqualTo(201);
        return created.json().get("id").longValue();
    }

    void deletePost(long id) throws Exception {
        send(RestCall.json("alice", "DELETE", "/wp-json/wp/v2/posts/" + id + "?force=true", "{}"));
    }

    JsonNode settings() throws Exception {
        return send(RestCall.get("alice", "/wp-json/wp/v2/settings")).json();
    }

    /** A settings write as alice, followed by a GET. */
    void setting(String target, String json) throws Exception {
        send(RestCall.json("alice", "POST", target, json));
        send(RestCall.get("alice", "/wp-json/wp/v2/settings"));
    }
}
