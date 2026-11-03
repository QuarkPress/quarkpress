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
 * and bob's). Later tasks grow it with the item, create, update and delete decisions.
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
