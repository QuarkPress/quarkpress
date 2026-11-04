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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * The read permission matrix of the post collection: role (anonymous, administrator, editor bob, author carol,
 * contributor dave, subscriber erin, the outsider gina with no role on the site, and frank, administrator of another
 * site only) by status (publish, future, draft, pending, private, trash, auto-draft) by own and others' posts (carol's
 * and bob's), for the collection and for the item in the view and the edit context; the create decision per caller,
 * type (posts, pages, books) and requested status; the update decision per caller, status and own or others' post,
 * the delete decision per caller, status and own or others' post; and the item's {@code Allow}.
 */
class PostPermissionMatrixDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
            PostPermissionMatrixDbTest.class, BookType.class, PostFixtures.class, PostFixtures.Builder.class);

    static final List<String> STATUSES =
            List.of("publish", "future", "draft", "pending", "private", "trash", "auto-draft");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static String u;

    /** The callers by name; null for the anonymous caller. */
    static final Map<String, String> CALLERS = new LinkedHashMap<>();

    /** carol's and bob's posts by status. */
    static final Map<String, List<Long>> POSTS = new LinkedHashMap<>();

    static long carol;
    static long bob;

    @BeforeAll
    static void matrix() {
        data = Jdbc.dataSource();
        site = TestData.site(data);
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        u = UUID.randomUUID().toString();
        CALLERS.put("anonymous", null);
        CALLERS.put("administrator", "test-" + member("administrator"));
        bob = member("editor");
        CALLERS.put("bob", "test-" + bob);
        carol = member("author");
        CALLERS.put("carol", "test-" + carol);
        CALLERS.put("dave", "test-" + member("contributor"));
        CALLERS.put("erin", "test-" + member("subscriber"));
        // gina: a network user with no role on the site (a member of the main site only)
        UserId gina = TestData.memberOfMain(data, "subscriber");
        CALLERS.put("gina", "test-" + gina.value());
        // frank: administrator of another site only
        UserId frank = TestData.profile(data, "frank");
        TestData.member(data, TestData.site(data), frank, "[\"administrator\"]");
        CALLERS.put("frank", "test-" + frank.value());
        int second = 0;
        for (String status : STATUSES) {
            List<Long> ids = new ArrayList<>();
            for (long author : List.of(carol, bob)) {
                boolean floating = status.equals("draft") || status.equals("pending") || status.equals("auto-draft");
                String when = (status.equals("future") ? "2030-09-01T10:00:" : "2026-09-01T10:00:")
                        + (second < 10 ? "0" : "") + second + "Z";
                ids.add(PostFixtures.builder(site)
                        .status(status)
                        .author(author)
                        .title(status + " " + author + " " + u)
                        .publishedAt(floating ? null : when)
                        .modifiedAt("2026-09-01T10:00:" + (second < 10 ? "0" : "") + second + "Z")
                        .insert(data)
                        .value());
                second++;
            }
            POSTS.put(status, ids);
        }
    }

    private static long member(String role) {
        UserId user = TestData.profile(data, role);
        TestData.member(data, site, user, "[\"" + role + "\"]");
        return user.value();
    }

    static RequestSpecification as(String bearer) {
        return Http.at(host, bearer);
    }

    /**
     * One row of the matrix: what the caller sees (ids, and the total when it differs from their count) for the
     * query, or the refusal's detail status.
     */
    record Row(String caller, String query, String status, List<Long> expected, long total, int refused) {

        static Row sees(String caller, String status, List<Long> expected) {
            return new Row(caller, "status=" + status + "&context=view", status, expected, expected.size(), 0);
        }

        static Row refused(String caller, String status, int detail) {
            return new Row(caller, "status=" + status + "&context=view", status, List.of(), 0, detail);
        }
    }

    @Test
    void collectionVisibility() {
        List<Row> rows = new ArrayList<>();
        for (String caller : List.of("anonymous", "erin", "gina", "frank")) {
            int detail = caller.equals("anonymous") ? 401 : 403;
            for (String status : STATUSES) {
                rows.add(
                        status.equals("publish")
                                ? Row.sees(caller, status, POSTS.get(status))
                                : Row.refused(caller, status, detail));
            }
        }
        for (String status : STATUSES) {
            // carol reads her own posts of every status (an author reads her own private post)
            rows.add(Row.sees("carol", status, status.equals("publish") ? POSTS.get(status) : own(status, carol)));
            // dave (a contributor with edit_posts) may ask, and owns no post
            rows.add(Row.sees("dave", status, status.equals("publish") ? POSTS.get(status) : List.of()));
            rows.add(Row.sees("bob", status, POSTS.get(status)));
            rows.add(Row.sees("administrator", status, POSTS.get(status)));
        }
        // the default status is publish for every caller, administrators included
        for (String caller : CALLERS.keySet()) {
            rows.add(new Row(caller, "context=view", "publish", POSTS.get("publish"), 2, 0));
        }
        // the edit context lists only what the caller may edit, after the totals are counted
        rows.add(new Row("erin", "context=edit", "publish", List.of(), 2, 0));
        rows.add(new Row("carol", "context=edit", "publish", own("publish", carol), 2, 0));
        rows.add(new Row("bob", "context=edit", "publish", POSTS.get("publish"), 2, 0));
        for (Row row : rows) {
            Response answer = as(CALLERS.get(row.caller()))
                    .get("/wp-json/wp/v2/posts?" + row.query() + "&search=" + u + "&_fields=id,status,author");
            String what = row.caller() + " ?" + row.query();
            if (row.refused() > 0) {
                assertThat(answer.statusCode()).as(what).isEqualTo(400);
                JsonNode error = json(answer.asString());
                assertThat(error.get("code").asText()).as(what).isEqualTo("rest_invalid_param");
                assertThat(error.get("message").asText()).as(what).isEqualTo("Invalid parameter(s): status");
                assertThat(error.at("/data/params/status").asText()).as(what).isEqualTo("Status is forbidden.");
                assertThat(error.at("/data/details/status/message").asText())
                        .as(what)
                        .isEqualTo("Status is forbidden.");
                assertThat(error.at("/data/details/status/code").asText())
                        .as(what)
                        .isEqualTo("rest_forbidden_status");
                assertThat(error.at("/data/details/status/data/status").asInt())
                        .as(what)
                        .isEqualTo(row.refused());
                continue;
            }
            assertThat(answer.statusCode()).as(what + ": " + answer.asString()).isEqualTo(200);
            assertThat(ids(answer)).as(what).containsExactlyInAnyOrderElementsOf(row.expected());
            assertThat(answer.header("X-WP-Total")).as(what).isEqualTo(Long.toString(row.total()));
            for (JsonNode item : json(answer.asString())) {
                assertThat(item.get("status").asText()).as(what).isEqualTo(row.status());
            }
        }
    }

    /** One cell of the item matrix: the status the caller gets for the post, in the view or the edit context. */
    record Cell(String caller, long post, boolean edit, int status) {}

    /**
     * {@code GET /posts/<id>} and {@code ?context=edit} for every caller, status and own or others' post: a published
     * post reads for everyone; the other statuses for whoever may edit the post (an author her own, an editor any),
     * a private one also for {@code read_private_posts}; the edit context needs {@code edit_post} on every status, so
     * an author is refused on a colleague's published post. Refusals are 401 for the anonymous caller, 403 for the
     * others; frank and gina see published posts only.
     */
    @Test
    void itemVisibility() {
        List<Cell> cells = new ArrayList<>();
        for (String status : STATUSES) {
            boolean published = status.equals("publish");
            for (long post : POSTS.get(status)) {
                boolean carols = post == own(status, carol).get(0);
                cells.add(new Cell("anonymous", post, false, published ? 200 : 401));
                cells.add(new Cell("anonymous", post, true, 401));
                for (String outsider : List.of("erin", "gina", "frank", "dave")) {
                    cells.add(new Cell(outsider, post, false, published ? 200 : 403));
                    cells.add(new Cell(outsider, post, true, 403));
                }
                cells.add(new Cell("carol", post, false, published || carols ? 200 : 403));
                cells.add(new Cell("carol", post, true, carols ? 200 : 403));
                for (String editor : List.of("bob", "administrator")) {
                    cells.add(new Cell(editor, post, false, 200));
                    cells.add(new Cell(editor, post, true, 200));
                }
            }
        }
        for (Cell cell : cells) {
            String what = cell.caller() + " GET /posts/" + cell.post() + (cell.edit() ? "?context=edit" : "");
            Response answer = as(CALLERS.get(cell.caller()))
                    .get("/wp-json/wp/v2/posts/" + cell.post() + (cell.edit() ? "?context=edit" : ""));
            assertThat(answer.statusCode()).as(what + ": " + answer.asString()).isEqualTo(cell.status());
            JsonNode body = json(answer.asString());
            if (cell.status() == 200) {
                assertThat(body.get("id").asLong()).as(what).isEqualTo(cell.post());
                assertThat(body.has("password")).as(what).isEqualTo(cell.edit());
            } else {
                assertThat(body.get("code").asText())
                        .as(what)
                        .isEqualTo(cell.edit() ? "rest_forbidden_context" : "rest_forbidden");
                assertThat(body.get("message").asText())
                        .as(what)
                        .isEqualTo(
                                cell.edit()
                                        ? "Sorry, you are not allowed to edit this post."
                                        : "Sorry, you are not allowed to do that.");
            }
        }
    }

    /**
     * {@code POST /<base>} for every caller, type and requested status: creating needs the type's {@code create_posts}
     * (an author and a contributor have none for pages; the subscriber, the outsider gina and frank, another site's
     * administrator, none at all), publishing and private posts its {@code publish_posts} (a contributor has none).
     * Refusals are 401 for the anonymous caller, 403 for the others.
     */
    @Test
    void createCells() {
        String created = "created " + UUID.randomUUID();
        for (String caller : CALLERS.keySet()) {
            for (String base : List.of("posts", "pages", "books")) {
                for (String status : List.of("draft", "pending", "publish", "private")) {
                    String what = caller + " POST /" + base + " " + status;
                    Response answer = as(CALLERS.get(caller))
                            .contentType("application/json")
                            .body("{\"title\":\"" + created + "\",\"status\":\"" + status + "\"}")
                            .post("/wp-json/wp/v2/" + base);
                    JsonNode body = json(answer.asString());
                    String refusal = createRefusal(caller, base, status);
                    if (refusal == null) {
                        assertThat(answer.statusCode())
                                .as(what + ": " + answer.asString())
                                .isEqualTo(201);
                        assertThat(body.get("status").asText()).as(what).isEqualTo(status);
                        continue;
                    }
                    assertThat(answer.statusCode())
                            .as(what + ": " + answer.asString())
                            .isEqualTo(caller.equals("anonymous") ? 401 : 403);
                    assertThat(body.get("code").asText())
                            .as(what)
                            .isEqualTo(refusal.equals("create") ? "rest_cannot_create" : "rest_cannot_publish");
                    assertThat(body.get("message").asText())
                            .as(what)
                            .isEqualTo(
                                    switch (refusal) {
                                        case "create" -> "Sorry, you are not allowed to create posts as this user.";
                                        case "private" ->
                                            "Sorry, you are not allowed to create private posts in this post type.";
                                        default -> "Sorry, you are not allowed to publish posts in this post type.";
                                    });
                }
            }
        }
    }

    /**
     * {@code PUT /posts/<id>} for every caller, status and own or others' post (posts of their own, so the other
     * cells keep theirs): updating needs {@code edit_post}, as the edit context does, so an author updates her own
     * posts of every status and an editor or administrator anyone's; the others are refused with
     * {@code rest_cannot_edit}, 401 for the anonymous caller, 403 for the others (frank and gina included). A missing
     * post is 404 for everyone.
     */
    @Test
    void updateCells() {
        String marker = "update " + UUID.randomUUID();
        Map<String, List<Long>> targets = new LinkedHashMap<>();
        int second = 0;
        for (String status : STATUSES) {
            List<Long> ids = new ArrayList<>();
            for (long author : List.of(carol, bob)) {
                boolean floating = status.equals("draft") || status.equals("pending") || status.equals("auto-draft");
                String when = (status.equals("future") ? "2030-09-02T10:00:" : "2026-09-02T10:00:")
                        + (second < 10 ? "0" : "") + second + "Z";
                ids.add(PostFixtures.builder(site)
                        .status(status)
                        .author(author)
                        .title(marker + " " + status)
                        .publishedAt(floating ? null : when)
                        .modifiedAt(when)
                        .insert(data)
                        .value());
                second++;
            }
            targets.put(status, ids);
        }
        for (String caller : CALLERS.keySet()) {
            for (String status : STATUSES) {
                for (int i = 0; i < 2; i++) {
                    long post = targets.get(status).get(i);
                    boolean carols = i == 0;
                    boolean allowed = caller.equals("bob")
                            || caller.equals("administrator")
                            || (caller.equals("carol") && carols);
                    String what =
                            caller + " PUT /posts/" + post + " (" + status + ", " + (carols ? "carol" : "bob") + "'s)";
                    Response answer = as(CALLERS.get(caller))
                            .contentType("application/json")
                            .body("{\"excerpt\":\"by " + caller + "\"}")
                            .put("/wp-json/wp/v2/posts/" + post);
                    JsonNode body = json(answer.asString());
                    if (allowed) {
                        assertThat(answer.statusCode())
                                .as(what + ": " + answer.asString())
                                .isEqualTo(200);
                        assertThat(body.at("/excerpt/raw").asText()).as(what).isEqualTo("by " + caller);
                        assertThat(body.get("status").asText()).as(what).isEqualTo(status);
                        continue;
                    }
                    assertThat(answer.statusCode())
                            .as(what + ": " + answer.asString())
                            .isEqualTo(caller.equals("anonymous") ? 401 : 403);
                    assertThat(body.get("code").asText()).as(what).isEqualTo("rest_cannot_edit");
                    assertThat(body.get("message").asText())
                            .as(what)
                            .isEqualTo("Sorry, you are not allowed to edit this post.");
                }
            }
            Response missing = as(CALLERS.get(caller))
                    .contentType("application/json")
                    .body("{}")
                    .put("/wp-json/wp/v2/posts/999999");
            assertThat(missing.statusCode()).as(caller + " PUT a missing post").isEqualTo(404);
            assertThat(json(missing.asString()).get("code").asText()).isEqualTo("rest_post_invalid_id");
        }
        assertThat(Jdbc.count(
                        data,
                        "select count(*) from qp_post where site_id = ? and title like ? and excerpt = ''",
                        site.value(),
                        marker + "%"))
                .as("the allowed updates were written")
                .isEqualTo(0);
    }

    /**
     * The item's {@code Allow} lists the writing methods for a caller who may update and delete the post: an
     * administrator on her own post gets {@code GET, POST, PUT, PATCH, DELETE}, the subscriber {@code GET} on a
     * published post, an author {@code GET} on a colleague's published post; a missing post has none.
     */
    @Test
    void allowHeaders() {
        long administrator = Long.parseLong(CALLERS.get("administrator").substring("test-".length()));
        long own = PostFixtures.builder(site)
                .status("publish")
                .author(administrator)
                .title("allow " + UUID.randomUUID())
                .publishedAt("2026-09-03T10:00:00Z")
                .modifiedAt("2026-09-03T10:00:00Z")
                .insert(data)
                .value();
        assertThat(as(CALLERS.get("administrator"))
                        .get("/wp-json/wp/v2/posts/" + own)
                        .header("Allow"))
                .isEqualTo("GET, POST, PUT, PATCH, DELETE");
        assertThat(as(CALLERS.get("erin")).get("/wp-json/wp/v2/posts/" + own).header("Allow"))
                .isEqualTo("GET");
        assertThat(as(CALLERS.get("carol")).get("/wp-json/wp/v2/posts/" + own).header("Allow"))
                .isEqualTo("GET");
        long carols = POSTS.get("publish").get(0);
        assertThat(as(CALLERS.get("carol"))
                        .get("/wp-json/wp/v2/posts/" + carols)
                        .header("Allow"))
                .isEqualTo("GET, POST, PUT, PATCH, DELETE");
        Response missing = as(null).get("/wp-json/wp/v2/posts/999999");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.header("Allow")).isNull();
    }

    /**
     * {@code DELETE /posts/<id>} (no {@code force}) for every caller, status and own or others' post (posts of their
     * own per caller, as a delete changes them): deleting needs {@code delete_post}, so an author trashes her own
     * posts of every status and an editor or administrator anyone's, a post already in the trash answering 410
     * {@code rest_already_trashed}; the others are refused with {@code rest_cannot_delete}, 401 for the anonymous
     * caller, 403 for the others (dave, erin, gina and frank), whatever the status. A missing post is 404 for
     * everyone.
     */
    @Test
    void deleteCells() {
        for (String caller : CALLERS.keySet()) {
            String marker = "delete " + caller + " " + UUID.randomUUID();
            int second = 0;
            for (String status : STATUSES) {
                for (long author : List.of(carol, bob)) {
                    boolean floating =
                            status.equals("draft") || status.equals("pending") || status.equals("auto-draft");
                    String when = (status.equals("future") ? "2030-09-04T10:00:" : "2026-09-04T10:00:")
                            + (second < 10 ? "0" : "") + second + "Z";
                    second++;
                    long post = PostFixtures.builder(site)
                            .status(status)
                            .author(author)
                            .title(marker + " " + status)
                            .publishedAt(floating ? null : when)
                            .modifiedAt(when)
                            .insert(data)
                            .value();
                    boolean carols = author == carol;
                    boolean allowed = caller.equals("bob")
                            || caller.equals("administrator")
                            || (caller.equals("carol") && carols);
                    String what = caller + " DELETE /posts/" + post + " (" + status + ", " + (carols ? "carol" : "bob")
                            + "'s)";
                    Response answer = as(CALLERS.get(caller)).delete("/wp-json/wp/v2/posts/" + post);
                    JsonNode body = json(answer.asString());
                    String stored = Jdbc.strings(data, "select status from qp_post where id = ?", post)
                            .get(0);
                    if (allowed && status.equals("trash")) {
                        assertThat(answer.statusCode())
                                .as(what + ": " + answer.asString())
                                .isEqualTo(410);
                        assertThat(body.get("code").asText()).as(what).isEqualTo("rest_already_trashed");
                        assertThat(body.get("message").asText())
                                .as(what)
                                .isEqualTo("The post has already been deleted.");
                        continue;
                    }
                    if (allowed) {
                        assertThat(answer.statusCode())
                                .as(what + ": " + answer.asString())
                                .isEqualTo(200);
                        assertThat(body.get("status").asText()).as(what).isEqualTo("trash");
                        assertThat(stored).as(what).isEqualTo("trash");
                        continue;
                    }
                    assertThat(answer.statusCode())
                            .as(what + ": " + answer.asString())
                            .isEqualTo(caller.equals("anonymous") ? 401 : 403);
                    assertThat(body.get("code").asText()).as(what).isEqualTo("rest_cannot_delete");
                    assertThat(body.get("message").asText())
                            .as(what)
                            .isEqualTo("Sorry, you are not allowed to delete this post.");
                    assertThat(stored).as(what).isEqualTo(status);
                }
            }
            Response missing = as(CALLERS.get(caller)).delete("/wp-json/wp/v2/posts/999999");
            assertThat(missing.statusCode())
                    .as(caller + " DELETE a missing post")
                    .isEqualTo(404);
            assertThat(json(missing.asString()).get("code").asText()).isEqualTo("rest_post_invalid_id");
        }
    }

    /** The refusal of a create: {@code create}, {@code private}, {@code publish}, or null when it is allowed. */
    private static String createRefusal(String caller, String base, String status) {
        switch (caller) {
            case "anonymous", "erin", "gina", "frank" -> {
                return "create";
            }
            case "carol" -> {
                return base.equals("pages") ? "create" : null;
            }
            case "dave" -> {
                if (base.equals("pages")) {
                    return "create";
                }
                return switch (status) {
                    case "publish" -> "publish";
                    case "private" -> "private";
                    default -> null;
                };
            }
            default -> {
                return null;
            }
        }
    }

    static List<Long> ids(Response response) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : json(response.asString())) {
            ids.add(item.get("id").asLong());
        }
        return ids;
    }

    static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Long> own(String status, long author) {
        List<Long> ids = POSTS.get(status);
        return List.of(author == carol ? ids.get(0) : ids.get(1));
    }
}
