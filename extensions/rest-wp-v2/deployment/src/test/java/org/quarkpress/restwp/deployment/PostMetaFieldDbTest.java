package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.MetaKeyDefinition;
import org.quarkpress.posts.MetaKeyProvider;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.Posts;

/**
 * The {@code meta} field on writes: the argument (an object or a list, null as absent), each registered key in
 * registration order (unknown keys ignored), its validation with the {@code meta.<name>} parameter, the key's
 * sanitizer for the caller, the permission per key (an unchanged value needs none), deletes, lists of values, the
 * errors of several keys in one answer, and the whole write rolled back when a key fails, the revision included.
 * Members of the site (UTC): alice (administrator and super admin), the editor bob, the author carol, the
 * contributor dave and the subscriber erin. {@link BookMeta} registers for {@code book}: {@code book_rating}
 * (integer, default 3), {@code book_tags} (a list of strings) and the protected {@code _book_code}, which only the
 * post's author may write.
 */
class PostMetaFieldDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
            PostMetaFieldDbTest.class,
            PostMetaFieldDbTest.BookMeta.class,
            BookType.class,
            PostFixtures.class,
            PostFixtures.Builder.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The test's keys of {@code book}; {@code _book_code} is writable by the post's author alone. */
    @ApplicationScoped
    public static class BookMeta implements MetaKeyProvider {

        @Inject
        Posts posts;

        @Override
        public List<MetaKeyDefinition> metaKeys(PostTypes types) {
            return List.of(
                    MetaKeyDefinition.builder("book_rating")
                            .subtype("book")
                            .type("integer")
                            .defaultValue(IntNode.valueOf(3))
                            .single()
                            .showInRest()
                            .build(),
                    MetaKeyDefinition.builder("book_tags")
                            .subtype("book")
                            .type("string")
                            .showInRest()
                            .build(),
                    MetaKeyDefinition.builder("_book_code")
                            .subtype("book")
                            .type("string")
                            .single()
                            .showInRest()
                            .auth((allowed, key, post, user, capability) -> user.isPresent()
                                    && posts.find(post)
                                            .flatMap(PostSnapshot::author)
                                            .equals(user))
                            .build());
        }
    }

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long alice;
    static long bob;
    static long carol;
    static long dave;
    static long erin;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.siteIn(data, "UTC");
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        alice = member("administrator");
        TestData.superAdmin(data, UserId.of(alice));
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

    static RequestSpecification as(long user) {
        return Http.at(host, "test-" + user).contentType("application/json");
    }

    static Response put(long user, String base, PostId post, String body) {
        return as(user).body(body).put("/wp-json/wp/v2/" + base + "/" + post.value());
    }

    private static PostId draft(long author, String type, String title) {
        return PostFixtures.builder(site)
                .type(type)
                .status("draft")
                .author(author)
                .title(title)
                .publishedAt(null)
                .insert(data);
    }

    private static PostId published(long author, String type, String title) {
        return PostFixtures.builder(site).type(type).author(author).title(title).insert(data);
    }

    /** The stored meta of the post, as the posts module keeps it: each key's list of values. */
    private static JsonNode storedMeta(PostId post) {
        List<String> meta = Jdbc.strings(data, "select meta from qp_post where id = ?", post.value());
        return json(meta.get(0) == null ? "{}" : meta.get(0));
    }

    private static String stored(PostId post, String column) {
        return Jdbc.strings(data, "select " + column + " from qp_post where id = ?", post.value())
                .get(0);
    }

    private static long revisions(PostId post) {
        return Jdbc.count(
                data, "select count(*) from qp_revision where post_id = ? and autosave = false", post.value());
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode meta(Response response) {
        return json(response.then().statusCode(200).extract().asString()).get("meta");
    }

    @Test
    void footnotesTypeErrorsUseTheAdditionalDataShape() {
        PostId carols = draft(carol, "post", "footnotes " + UUID.randomUUID());
        put(carol, "posts", carols, "{\"meta\":{\"footnotes\":5}}")
                .then()
                .statusCode(400)
                .body(equalTo("{\"code\":\"rest_invalid_type\",\"message\":\"meta.footnotes is not of type string.\","
                        + "\"data\":{\"status\":400},\"additional_data\":[{\"param\":\"meta.footnotes\"}]}"));
        assertThat(storedMeta(carols).has("footnotes")).isFalse();
    }

    @Test
    void unknownKeysAreIgnoredAndNonObjectsRefused() {
        PostId carols = draft(carol, "post", "unknown " + UUID.randomUUID());
        assertThat(meta(put(carol, "posts", carols, "{\"meta\":{\"nope\":1,\"book_rating\":4}}"))
                        .toString())
                .isEqualTo("{\"footnotes\":\"\"}");
        assertThat(storedMeta(carols).has("nope")).isFalse();
        assertThat(storedMeta(carols).has("book_rating")).isFalse();
        String refused = "{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parameter(s): meta\","
                + "\"data\":{\"status\":400,\"params\":{\"meta\":\"Invalid parameter.\"},\"details\":[]}}";
        put(carol, "posts", carols, "{\"meta\":\"x\"}").then().statusCode(400).body(equalTo(refused));
        as(carol)
                .body("{\"title\":\"refused meta\",\"meta\":\"x\"}")
                .post("/wp-json/wp/v2/posts")
                .then()
                .statusCode(400)
                .body(equalTo(refused));
        put(carol, "posts", carols, "{\"meta\":null}").then().statusCode(200);
        // a PHP list names no registered key
        put(carol, "posts", carols, "{\"meta\":[\"x\"]}").then().statusCode(200);
    }

    @Test
    void theInternalKeysAreNeverWrittenThroughMeta() {
        String internal = "{\"meta\":{\"_edit_lock\":\"1:1\",\"_edit_last\":\"9\",\"_thumbnail_id\":\"5\","
                + "\"_wp_page_template\":\"x\"}}";
        // a post without them, and one whose stored values must stay as they are
        PostId bare = draft(carol, "post", "internal bare " + UUID.randomUUID());
        PostId kept = draft(carol, "post", "internal kept " + UUID.randomUUID());
        String stored = "{\"_edit_lock\":[\"100:" + carol + "\"],\"_edit_last\":[\"" + carol
                + "\"],\"_thumbnail_id\":[\"0\"],\"_wp_page_template\":[\"default\"]}";
        Jdbc.update(data, "update qp_post set meta = " + TestDb.json() + " where id = ?", stored, kept.value());
        for (long editor : new long[] {carol, alice}) {
            assertThat(meta(put(editor, "posts", bare, internal)).toString()).isEqualTo("{\"footnotes\":\"\"}");
            assertThat(meta(put(editor, "posts", kept, internal)).toString()).isEqualTo("{\"footnotes\":\"\"}");
        }
        for (String key : new String[] {"_edit_lock", "_edit_last", "_thumbnail_id", "_wp_page_template"}) {
            assertThat(storedMeta(bare).has(key)).as(key).isFalse();
        }
        assertThat(storedMeta(kept).get("_edit_lock").toString()).isEqualTo("[\"100:" + carol + "\"]");
        assertThat(storedMeta(kept).get("_edit_last").toString()).isEqualTo("[\"" + carol + "\"]");
        assertThat(storedMeta(kept).get("_thumbnail_id").toString()).isEqualTo("[\"0\"]");
        assertThat(storedMeta(kept).get("_wp_page_template").toString()).isEqualTo("[\"default\"]");
    }

    @Test
    void contributorFootnotesAreSanitized() {
        String given = "[{\\\"id\\\":\\\"a\\\",\\\"content\\\":\\\"<script>x</script>/y\\\"}]";
        String sanitized = "[{\"id\":\"a\",\"content\":\"x\\/y\"}]";
        PostId daves = draft(dave, "post", "dave " + UUID.randomUUID());
        Response answer = put(dave, "posts", daves, "{\"meta\":{\"footnotes\":\"" + given + "\"}}");
        assertThat(meta(answer).get("footnotes").textValue()).isEqualTo(sanitized);
        assertThat(storedMeta(daves).get("footnotes").toString()).isEqualTo("[" + MAPPER.valueToTree(sanitized) + "]");
        // the administrator holds unfiltered_html: stored verbatim
        PostId alices = draft(alice, "post", "alice " + UUID.randomUUID());
        assertThat(meta(put(alice, "posts", alices, "{\"meta\":{\"footnotes\":\"" + given + "\"}}"))
                        .get("footnotes")
                        .textValue())
                .isEqualTo("[{\"id\":\"a\",\"content\":\"<script>x</script>/y\"}]");
        // not JSON: empty
        assertThat(meta(put(dave, "posts", daves, "{\"meta\":{\"footnotes\":\"not json\"}}"))
                        .get("footnotes")
                        .textValue())
                .isEqualTo("");
        assertThat(storedMeta(daves).get("footnotes").toString()).isEqualTo("[\"\"]");
        // a JSON object is a PHP array: its values are the entries, listed again
        assertThat(meta(put(
                                dave,
                                "posts",
                                daves,
                                "{\"meta\":{\"footnotes\":\"{\\\"x\\\":{\\\"id\\\":\\\"a\\\","
                                        + "\\\"content\\\":\\\"b\\\"}}\"}}"))
                        .get("footnotes")
                        .textValue())
                .isEqualTo("[{\"id\":\"a\",\"content\":\"b\"}]");
        // null deletes the row; the key reads as its empty value
        assertThat(meta(put(dave, "posts", daves, "{\"meta\":{\"footnotes\":null}}"))
                        .get("footnotes")
                        .textValue())
                .isEqualTo("");
        assertThat(storedMeta(daves).has("footnotes")).isFalse();
        // a create writes the meta too, sanitized for the caller
        String created = as(dave).body("{\"title\":\"dave created " + UUID.randomUUID()
                        + "\",\"meta\":{\"footnotes\":\"" + given + "\"}}")
                .post("/wp-json/wp/v2/posts")
                .then()
                .statusCode(201)
                .body("meta.footnotes", equalTo(sanitized))
                .extract()
                .asString();
        PostId createdId = PostId.of(json(created).get("id").longValue());
        assertThat(storedMeta(createdId).get("footnotes").toString())
                .isEqualTo("[" + MAPPER.valueToTree(sanitized) + "]");
    }

    @Test
    void permissionsPerKey() {
        String u = UUID.randomUUID().toString();
        PostId book = draft(carol, "book", "carol's book " + u);
        // the author may write the protected key
        assertThat(meta(put(carol, "books", book, "{\"meta\":{\"_book_code\":\"X1\"}}"))
                        .get("_book_code")
                        .textValue())
                .isEqualTo("X1");
        // the editor may edit the book but not the key; the whole write is rolled back
        put(bob, "books", book, "{\"title\":\"bob's title\",\"meta\":{\"_book_code\":\"X2\"}}")
                .then()
                .statusCode(403)
                .body(equalTo("{\"code\":\"rest_cannot_update\",\"message\":\"Sorry, you are not allowed to edit the"
                        + " _book_code custom field.\",\"data\":{\"key\":\"_book_code\",\"status\":403}}"));
        assertThat(stored(book, "title")).isEqualTo("carol's book " + u);
        assertThat(storedMeta(book).get("_book_code").toString()).isEqualTo("[\"X1\"]");
        put(bob, "books", book, "{\"meta\":{\"_book_code\":null}}")
                .then()
                .statusCode(403)
                .body(equalTo("{\"code\":\"rest_cannot_delete\",\"message\":\"Sorry, you are not allowed to edit the"
                        + " _book_code custom field.\",\"data\":{\"key\":\"_book_code\",\"status\":403}}"));
        // the unchanged value needs no permission; a key that is not protected needs edit_post only
        assertThat(meta(put(bob, "books", book, "{\"meta\":{\"_book_code\":\"X1\",\"book_rating\":4}}"))
                        .get("book_rating")
                        .intValue())
                .isEqualTo(4);
        // the subscriber never reaches the meta step
        put(erin, "books", book, "{\"meta\":{\"book_rating\":5}}")
                .then()
                .statusCode(403)
                .body("code", equalTo("rest_cannot_edit"))
                .body("message", equalTo("Sorry, you are not allowed to edit this post."));
        // the author deletes the protected key
        assertThat(meta(put(carol, "books", book, "{\"meta\":{\"_book_code\":null}}"))
                        .get("_book_code")
                        .textValue())
                .isEqualTo("");
        assertThat(storedMeta(book).has("_book_code")).isFalse();
    }

    @Test
    void errorsAggregateAndRollBack() {
        String u = UUID.randomUUID().toString();
        PostId book = published(carol, "book", "aggregate " + u);
        put(carol, "books", book, "{\"title\":\"aggregate one " + u + "\",\"meta\":{\"book_rating\":4}}")
                .then()
                .statusCode(200);
        long before = revisions(book);
        String modified = stored(book, "modified_at");
        put(carol, "books", book, "{\"title\":\"changed\",\"meta\":{\"book_rating\":\"x\",\"book_tags\":[null]}}")
                .then()
                .statusCode(400)
                .body(equalTo("{\"code\":\"rest_invalid_type\",\"message\":\"meta.book_rating is not of type"
                        + " integer.\",\"data\":{\"status\":400},\"additional_data\":[{\"param\":\"meta.book_rating\"}],"
                        + "\"additional_errors\":[{\"code\":\"rest_invalid_stored_value\",\"message\":\"The book_tags"
                        + " property has an invalid stored value, and cannot be updated to null.\","
                        + "\"data\":{\"status\":500}}]}"));
        assertThat(stored(book, "title")).isEqualTo("aggregate one " + u);
        assertThat(stored(book, "modified_at")).isEqualTo(modified);
        assertThat(storedMeta(book).get("book_rating").toString()).isEqualTo("[4]");
        assertThat(revisions(book)).isEqualTo(before);
        // the keys are tried in registration order, whatever order the request names them in
        put(carol, "books", book, "{\"meta\":{\"book_tags\":[null],\"book_rating\":\"x\"}}")
                .then()
                .statusCode(400)
                .body(equalTo("{\"code\":\"rest_invalid_type\",\"message\":\"meta.book_rating is not of type"
                        + " integer.\",\"data\":{\"status\":400},\"additional_data\":[{\"param\":\"meta.book_rating\"}],"
                        + "\"additional_errors\":[{\"code\":\"rest_invalid_stored_value\",\"message\":\"The book_tags"
                        + " property has an invalid stored value, and cannot be updated to null.\","
                        + "\"data\":{\"status\":500}}]}"));
        // a key that passes before another fails is rolled back with it
        put(carol, "books", book, "{\"meta\":{\"book_tags\":[\"kept\"],\"book_rating\":\"x\"}}")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_type"));
        assertThat(storedMeta(book).has("book_tags")).isFalse();
        // a create whose meta fails leaves no post
        String title = "created with error " + u;
        as(carol)
                .body("{\"title\":\"" + title + "\",\"meta\":{\"footnotes\":5}}")
                .post("/wp-json/wp/v2/posts")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_type"));
        assertThat(Jdbc.count(data, "select count(*) from qp_post where title = ?", title))
                .isZero();
    }

    @Test
    void deletingAnInvalidStoredValueIsRefused() {
        PostId book = draft(carol, "book", "invalid stored " + UUID.randomUUID());
        Jdbc.update(
                data,
                "update qp_post set meta = " + TestDb.json() + " where id = ?",
                "{\"book_rating\":[\"abc\"]}",
                book.value());
        put(carol, "books", book, "{\"meta\":{\"book_rating\":null}}")
                .then()
                .statusCode(500)
                .body(equalTo("{\"code\":\"rest_invalid_stored_value\",\"message\":\"The book_rating property has an"
                        + " invalid stored value, and cannot be updated to null.\",\"data\":{\"status\":500}}"));
        assertThat(storedMeta(book).get("book_rating").toString()).isEqualTo("[\"abc\"]");
    }

    @Test
    void valuesNestedDeeperThanStorageAreInvalidParameters() {
        PostId carols = draft(carol, "post", "deep " + UUID.randomUUID());
        // 64 nested lists still reach the key's schema; 65 are refused with the argument
        put(carol, "posts", carols, "{\"meta\":{\"footnotes\":" + nested(64) + "}}")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_type"));
        put(carol, "posts", carols, "{\"meta\":{\"footnotes\":" + nested(65) + "}}")
                .then()
                .statusCode(400)
                .body(equalTo("{\"code\":\"rest_invalid_param\",\"message\":\"Invalid parameter(s): meta\","
                        + "\"data\":{\"status\":400,\"params\":{\"meta\":\"Invalid parameter.\"},\"details\":[]}}"));
        // the autosave takes the same argument
        as(carol)
                .body("{\"content\":\"deep\",\"meta\":{\"footnotes\":" + nested(65) + "}}")
                .post("/wp-json/wp/v2/posts/" + carols.value() + "/autosaves")
                .then()
                .statusCode(400)
                .body("code", equalTo("rest_invalid_param"));
        // an unregistered key is ignored however deep
        put(carol, "posts", carols, "{\"meta\":{\"nope\":" + nested(65) + "}}")
                .then()
                .statusCode(200);
    }

    /** {@code depth} lists nested in one another. */
    private static String nested(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }

    @Test
    void multiKeysAndDefaults() {
        PostId book = draft(carol, "book", "multi " + UUID.randomUUID());
        JsonNode read = meta(as(carol).get("/wp-json/wp/v2/books/" + book.value() + "?context=edit"));
        assertThat(read.get("book_rating").intValue()).isEqualTo(3);
        assertThat(read.get("book_tags").toString()).isEqualTo("[]");
        assertThat(meta(put(carol, "books", book, "{\"meta\":{\"book_tags\":[\"a\",\"b\"]}}"))
                        .get("book_tags")
                        .toString())
                .isEqualTo("[\"a\",\"b\"]");
        assertThat(meta(put(carol, "books", book, "{\"meta\":{\"book_tags\":[\"b\",\"c\"]}}"))
                        .get("book_tags")
                        .toString())
                .isEqualTo("[\"b\",\"c\"]");
        assertThat(meta(put(carol, "books", book, "{\"meta\":{\"book_tags\":[]}}"))
                        .get("book_tags")
                        .toString())
                .isEqualTo("[]");
        assertThat(storedMeta(book).has("book_tags")).isFalse();
        // a numeric text is coerced to the key's type
        assertThat(meta(put(carol, "books", book, "{\"meta\":{\"book_rating\":\"5\"}}"))
                        .get("book_rating")
                        .intValue())
                .isEqualTo(5);
        assertThat(storedMeta(book).get("book_rating").toString()).isEqualTo("[5]");
        assertThat(meta(put(carol, "books", book, "{\"meta\":{\"book_rating\":null}}"))
                        .get("book_rating")
                        .intValue())
                .isEqualTo(3);
        assertThat(storedMeta(book).has("book_rating")).isFalse();
    }

    @Test
    void theRevisionCopiesTheMetaOfTheSameRequest() {
        PostId post = published(alice, "post", "revisioned " + UUID.randomUUID());
        put(alice, "posts", post, "{\"content\":\"first\"}").then().statusCode(200);
        long before = revisions(post);
        String footnotes = "[{\\\"id\\\":\\\"n\\\",\\\"content\\\":\\\"a note\\\"}]";
        put(alice, "posts", post, "{\"meta\":{\"footnotes\":\"" + footnotes + "\"}}")
                .then()
                .statusCode(200);
        // a footnotes-only change is a change: the new revision holds the new footnotes
        assertThat(revisions(post)).isEqualTo(before + 1);
        String latest = Jdbc.strings(
                        data,
                        "select meta from qp_revision where post_id = ? and autosave = false order by id desc",
                        post.value())
                .get(0);
        assertThat(json(latest).get("footnotes").toString()).contains("a note");
    }
}
