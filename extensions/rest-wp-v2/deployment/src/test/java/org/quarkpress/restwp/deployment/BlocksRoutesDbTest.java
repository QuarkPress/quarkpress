package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * Synced patterns ({@code /wp/v2/blocks}): the posts routes with WordPress's blocks controller differences — the raw
 * title and content in the view context and no rendered ones, the sync status lifted out of {@code meta} to a
 * top-level key (empty when {@code meta} is not computed), the type's read capability ({@code edit_posts}) checked
 * before the posts' read rule, for an item as for each collection row, and the revisions keeping the key inside
 * {@code meta} with the revision's own value. Members of each site (UTC): alice (administrator and super admin), the
 * editor bob, the author carol, the contributor dave and the subscriber erin. The reading site holds alice's synced
 * "HAR pattern", carol's synced "Carol pattern" and alice's unsynced "Unsynced pattern"; writes go to a site of their
 * own so the reading site's totals stay fixed.
 */
class BlocksRoutesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            RestApp.network(BlocksRoutesDbTest.class, PostFixtures.class, PostFixtures.Builder.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONTENT = "<!-- wp:paragraph --><p>Pattern text.</p><!-- /wp:paragraph -->";

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static SiteId writes;
    static String writesHost;
    static long alice;
    static long bob;
    static long carol;
    static long dave;
    static long erin;
    static PostId har;
    static PostId carols;
    static PostId unsynced;

    @BeforeAll
    static void fixtures() {
        data = Jdbc.dataSource();
        site = TestData.siteIn(data, "UTC");
        writes = TestData.siteIn(data, "UTC");
        host = domain(site);
        writesHost = domain(writes);
        alice = member("administrator");
        TestData.superAdmin(data, UserId.of(alice));
        bob = member("editor");
        carol = member("author");
        dave = member("contributor");
        erin = member("subscriber");
        har = block(alice, "HAR pattern", "2026-11-01T10:00:01Z");
        carols = block(carol, "Carol pattern", "2026-11-01T10:00:02Z");
        unsynced = block(alice, "Unsynced pattern", "2026-11-01T10:00:03Z");
        Jdbc.update(
                data,
                "update qp_post set meta = " + TestDb.json() + " where id = ?",
                "{\"wp_pattern_sync_status\":[\"unsynced\"]}",
                unsynced.value());
    }

    private static String domain(SiteId id) {
        return Jdbc.strings(data, "select domain from qp_site where id = ?", id.value())
                .get(0);
    }

    private static long member(String role) {
        UserId user = TestData.profile(data, role);
        TestData.member(data, site, user, "[\"" + role + "\"]");
        TestData.member(data, writes, user, "[\"" + role + "\"]");
        return user.value();
    }

    private static PostId block(long author, String title, String iso) {
        return PostFixtures.builder(site)
                .type("wp_block")
                .author(author)
                .title(title)
                .content(CONTENT)
                .publishedAt(iso)
                .modifiedAt(iso)
                .insert(data);
    }

    /** A request to the site with {@code host} as the user, or anonymous for 0. */
    static RequestSpecification as(String host, long user) {
        return Http.at(host, user == 0 ? null : "test-" + user);
    }

    static RequestSpecification reading(long user) {
        return as(host, user);
    }

    static RequestSpecification writing(long user) {
        return as(writesHost, user).contentType("application/json");
    }

    static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode ok(Response response) {
        return json(response.then().statusCode(200).extract().asString());
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    static List<Long> ids(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        list.forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    private static String block(PostId id) {
        return "/wp-json/wp/v2/blocks/" + id.value();
    }

    @Test
    void editShapeInOrder() {
        JsonNode edit = ok(reading(alice).get(block(har) + "?context=edit"));
        assertThat(fieldNames(edit))
                .containsExactly(
                        "id",
                        "date",
                        "date_gmt",
                        "guid",
                        "modified",
                        "modified_gmt",
                        "password",
                        "slug",
                        "status",
                        "type",
                        "link",
                        "title",
                        "content",
                        "excerpt",
                        "template",
                        "meta",
                        "wp_pattern_category",
                        "wp_pattern_sync_status",
                        "_links");
        assertThat(fieldNames(edit.get("guid"))).containsExactly("rendered", "raw");
        assertThat(edit.get("title").toString()).isEqualTo("{\"raw\":\"HAR pattern\"}");
        assertThat(edit.get("content").toString())
                .isEqualTo("{\"raw\":" + MAPPER.valueToTree(CONTENT) + ",\"protected\":false,\"block_version\":1}");
        assertThat(fieldNames(edit.get("excerpt"))).containsExactly("raw", "rendered", "protected");
        assertThat(edit.get("meta").toString()).isEqualTo("{\"footnotes\":\"\"}");
        assertThat(edit.get("wp_pattern_category").toString()).isEqualTo("[]");
        assertThat(edit.get("wp_pattern_sync_status").asText()).isEmpty();
        assertThat(edit.get("type").asText()).isEqualTo("wp_block");
    }

    @Test
    void viewAndEmbedShapes() {
        Response view = reading(dave).get(block(har));
        view.then().statusCode(200).header("Allow", "GET");
        // the type is not viewable: no alternate link
        assertThat(view.headers().getValues("Link")).noneMatch(link -> link.contains("alternate"));
        JsonNode body = json(view.asString());
        assertThat(fieldNames(body))
                .containsExactly(
                        "id",
                        "date",
                        "date_gmt",
                        "guid",
                        "modified",
                        "modified_gmt",
                        "slug",
                        "status",
                        "type",
                        "link",
                        "title",
                        "content",
                        "excerpt",
                        "template",
                        "meta",
                        "wp_pattern_category",
                        "wp_pattern_sync_status",
                        "_links");
        assertThat(fieldNames(body.get("guid"))).containsExactly("rendered");
        assertThat(body.get("title").toString()).isEqualTo("{\"raw\":\"HAR pattern\"}");
        // the view content has no block version
        assertThat(body.get("content").toString())
                .isEqualTo("{\"raw\":" + MAPPER.valueToTree(CONTENT) + ",\"protected\":false}");
        assertThat(fieldNames(body.get("excerpt"))).containsExactly("rendered", "protected");
        assertThat(body.get("meta").toString()).isEqualTo("{\"footnotes\":\"\"}");

        String embed = reading(alice)
                .get(block(unsynced) + "?context=embed")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(fieldNames(json(embed)))
                .containsExactly(
                        "id", "date", "slug", "type", "link", "title", "excerpt", "wp_pattern_sync_status", "_links");
        // the title keeps nothing in the embed context and prints as PHP's empty array; meta is not computed there,
        // so even the unsynced pattern's status is empty
        assertThat(embed).contains("\"title\":[]").contains("\"wp_pattern_sync_status\":\"\"");
        assertThat(fieldNames(json(embed).get("excerpt"))).containsExactly("rendered", "protected");
    }

    @Test
    void theSyncStatusLift() {
        JsonNode view = ok(reading(alice).get(block(unsynced)));
        assertThat(view.get("wp_pattern_sync_status").asText()).isEqualTo("unsynced");
        assertThat(view.get("meta").toString()).isEqualTo("{\"footnotes\":\"\"}");
        // the status alone does not compute meta, so it is empty
        assertThat(reading(alice)
                        .get(block(unsynced) + "?_fields=wp_pattern_sync_status")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .isEqualTo("{\"wp_pattern_sync_status\":\"\"}");
        assertThat(reading(alice)
                        .get(block(unsynced) + "?_fields=meta,wp_pattern_sync_status")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .isEqualTo("{\"meta\":{\"footnotes\":\"\"},\"wp_pattern_sync_status\":\"unsynced\"}");
        assertThat(reading(alice)
                        .get(block(unsynced) + "?_fields=id,title")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString())
                .isEqualTo("{\"id\":" + unsynced.value() + ",\"title\":{\"raw\":\"Unsynced pattern\"}}");
    }

    @Test
    void syncStatusWrites() {
        JsonNode created = json(writing(alice)
                .body("{\"title\":\"U\",\"status\":\"publish\",\"content\":\"<!-- wp:paragraph --><p>u</p>"
                        + "<!-- /wp:paragraph -->\",\"meta\":{\"wp_pattern_sync_status\":\"unsynced\"}}")
                .post("/wp-json/wp/v2/blocks")
                .then()
                .statusCode(201)
                .extract()
                .asString());
        assertThat(created.get("wp_pattern_sync_status").asText()).isEqualTo("unsynced");
        assertThat(created.get("meta").toString()).isEqualTo("{\"footnotes\":\"\"}");
        String item = "/wp-json/wp/v2/blocks/" + created.get("id").asLong();
        writing(alice)
                .body("{\"meta\":{\"wp_pattern_sync_status\":\"\"}}")
                .patch(item)
                .then()
                .statusCode(400)
                .header("Allow", "GET, POST, PUT, PATCH, DELETE")
                .body(equalTo("{\"code\":\"rest_not_in_enum\",\"message\":\"meta.wp_pattern_sync_status is not one"
                        + " of partial and unsynced.\",\"data\":{\"status\":400}}"));
        assertThat(ok(writing(alice)
                                .body("{\"meta\":{\"wp_pattern_sync_status\":null}}")
                                .patch(item))
                        .get("wp_pattern_sync_status")
                        .asText())
                .isEmpty();
        // the top-level key is no argument: ignored
        assertThat(ok(writing(alice)
                                .body("{\"wp_pattern_sync_status\":\"unsynced\"}")
                                .patch(item))
                        .get("wp_pattern_sync_status")
                        .asText())
                .isEmpty();
        assertThat(ok(as(writesHost, alice).get(item + "?context=edit"))
                        .get("wp_pattern_sync_status")
                        .asText())
                .isEmpty();
    }

    @Test
    void theRoleTable() {
        // erin and the anonymous caller read no pattern
        reading(erin)
                .get(block(har))
                .then()
                .statusCode(403)
                .body(equalTo("{\"code\":\"rest_forbidden\",\"message\":\"Sorry, you are not allowed to do that.\","
                        + "\"data\":{\"status\":403}}"));
        reading(0)
                .get(block(har))
                .then()
                .statusCode(401)
                .body(equalTo("{\"code\":\"rest_forbidden\",\"message\":\"Sorry, you are not allowed to do that.\","
                        + "\"data\":{\"status\":401}}"));
        // dave reads in the view context, not the edit one
        reading(dave).get(block(har)).then().statusCode(200).header("Allow", "GET");
        reading(dave)
                .get(block(har) + "?context=edit")
                .then()
                .statusCode(403)
                .body(equalTo("{\"code\":\"rest_forbidden_context\",\"message\":\"Sorry, you are not allowed to edit"
                        + " this post.\",\"data\":{\"status\":403}}"));
        // dave may not create
        writing(dave)
                .body("{\"title\":\"D\",\"status\":\"publish\",\"content\":\"x\"}")
                .post("/wp-json/wp/v2/blocks")
                .then()
                .statusCode(403)
                .header("Allow", "GET")
                .body(equalTo("{\"code\":\"rest_cannot_create\",\"message\":\"Sorry, you are not allowed to create"
                        + " posts as this user.\",\"data\":{\"status\":403}}"));
        // carol on alice's pattern: read only, also on her refused update
        reading(carol).get(block(har)).then().statusCode(200).header("Allow", "GET");
        reading(carol)
                .contentType("application/json")
                .body("{\"title\":\"carol\"}")
                .patch(block(har))
                .then()
                .statusCode(403)
                .header("Allow", "GET")
                .body(equalTo("{\"code\":\"rest_cannot_edit\",\"message\":\"Sorry, you are not allowed to edit this"
                        + " post.\",\"data\":{\"status\":403}}"));
        reading(carol)
                .get(block(carols) + "?context=edit")
                .then()
                .statusCode(200)
                .header("Allow", "GET, POST, PUT, PATCH, DELETE");
        // the collection's methods per caller
        reading(carol).options("/wp-json/wp/v2/blocks").then().statusCode(200).header("Allow", "GET, POST");
        reading(dave).options("/wp-json/wp/v2/blocks").then().statusCode(200).header("Allow", "GET");
        reading(0).options("/wp-json/wp/v2/blocks").then().statusCode(200).header("Allow", "GET");
        reading(erin)
                .get("/wp-json/wp/v2/blocks?context=edit")
                .then()
                .statusCode(403)
                .body(equalTo("{\"code\":\"rest_forbidden_context\",\"message\":\"Sorry, you are not allowed to edit"
                        + " posts in this post type.\",\"data\":{\"status\":403}}"));
    }

    @Test
    void collectionRowsNeedTheReadCapability() {
        // dave: every row in the view context, none in the edit context, the totals counting all three
        Response view = reading(dave).get("/wp-json/wp/v2/blocks");
        view.then().statusCode(200).header("X-WP-Total", "3").header("Allow", "GET");
        assertThat(ids(json(view.asString()))).containsExactly(unsynced.value(), carols.value(), har.value());
        Response edit = reading(dave).get("/wp-json/wp/v2/blocks?context=edit");
        edit.then().statusCode(200).header("X-WP-Total", "3");
        assertThat(edit.asString()).isEqualTo("[]");
        // the anonymous caller lists nothing, with the totals of the query
        Response anonymous = reading(0).get("/wp-json/wp/v2/blocks");
        anonymous
                .then()
                .statusCode(200)
                .header("X-WP-Total", "3")
                .header("X-WP-TotalPages", "1")
                .header("Allow", "GET");
        assertThat(anonymous.asString()).isEqualTo("[]");
        assertThat(reading(erin).get("/wp-json/wp/v2/blocks").asString()).isEqualTo("[]");
    }

    @Test
    void editContextListsOnlyEditableRows() {
        Response listed = reading(carol).get("/wp-json/wp/v2/blocks?context=edit&per_page=100&page=1");
        listed.then()
                .statusCode(200)
                .header("X-WP-Total", "3")
                .header("X-WP-TotalPages", "1")
                .header("Allow", "GET, POST");
        assertThat(ids(json(listed.asString()))).containsExactly(carols.value());
    }

    @Test
    void createUpdateDelete() {
        long category = json(as(writesHost, alice)
                        .contentType("application/json")
                        .body("{\"name\":\"Writes\"}")
                        .post("/wp-json/wp/v2/wp_pattern_category")
                        .then()
                        .statusCode(201)
                        .extract()
                        .asString())
                .get("id")
                .asLong();
        Response created = writing(carol)
                .body("{\"title\":\"Carol's\",\"content\":\"<!-- wp:paragraph --><p>c</p><!-- /wp:paragraph -->\","
                        + "\"status\":\"publish\",\"meta\":{\"wp_pattern_sync_status\":\"unsynced\"},"
                        + "\"wp_pattern_category\":[" + category + "]}")
                .post("/wp-json/wp/v2/blocks");
        created.then().statusCode(201).header("Allow", "GET, POST");
        JsonNode body = json(created.asString());
        long id = body.get("id").asLong();
        assertThat(created.header("Location")).endsWith("/wp-json/wp/v2/blocks/" + id);
        assertThat(body.get("wp_pattern_category").toString()).isEqualTo("[" + category + "]");
        assertThat(body.get("wp_pattern_sync_status").asText()).isEqualTo("unsynced");
        // listed by its category
        assertThat(ids(ok(as(writesHost, carol).get("/wp-json/wp/v2/blocks?wp_pattern_category=" + category))))
                .containsExactly(id);
        // the editor's update: POST with the method override
        String item = "/wp-json/wp/v2/blocks/" + id;
        JsonNode updated = ok(writing(carol)
                .header("X-HTTP-Method-Override", "PUT")
                .body("{\"id\":" + id + ",\"content\":\"<!-- wp:paragraph --><p>d</p><!-- /wp:paragraph -->\"}")
                .post(item));
        assertThat(updated.at("/content/raw").asText())
                .isEqualTo("<!-- wp:paragraph --><p>d</p><!-- /wp:paragraph -->");
        assertThat(updated.at("/_links/version-history/0/count").asInt()).isEqualTo(1);
        // DELETE trashes, force deletes
        assertThat(ok(as(writesHost, carol).delete(item)).get("status").asText())
                .isEqualTo("trash");
        JsonNode deleted = ok(as(writesHost, carol).delete(item + "?force=true"));
        assertThat(deleted.get("deleted").asBoolean()).isTrue();
        assertThat(deleted.at("/previous/wp_pattern_sync_status").asText()).isEqualTo("unsynced");
        as(writesHost, carol).get(item).then().statusCode(404).body("code", equalTo("rest_post_invalid_id"));
    }

    @Test
    void revisionsAndAutosavesRoutesExist() {
        for (String sync : List.of("", "unsynced")) {
            String meta = sync.isEmpty() ? "" : ",\"meta\":{\"wp_pattern_sync_status\":\"" + sync + "\"}";
            long id = json(writing(alice)
                            .body("{\"title\":\"R\",\"status\":\"publish\",\"content\":\"r\"" + meta + "}")
                            .post("/wp-json/wp/v2/blocks")
                            .then()
                            .statusCode(201)
                            .extract()
                            .asString())
                    .get("id")
                    .asLong();
            String item = "/wp-json/wp/v2/blocks/" + id;
            ok(writing(alice).body("{\"content\":\"updated\"}").post(item));
            JsonNode revisions = ok(as(writesHost, alice).get(item + "/revisions?context=edit"));
            assertThat(revisions).isNotEmpty();
            for (JsonNode revision : revisions) {
                // the revision's own meta, the status not lifted
                assertThat(revision.get("meta").toString())
                        .as(sync)
                        .isEqualTo("{\"wp_pattern_sync_status\":null,\"footnotes\":\"\"}");
                assertThat(revision.has("wp_pattern_sync_status")).isFalse();
            }
            as(writesHost, alice).get(item + "/autosaves").then().statusCode(200);
        }
    }

    @Test
    void optionsOfTheCollection() {
        JsonNode options = ok(reading(alice).options("/wp-json/wp/v2/blocks"));
        assertThat(options.get("methods").toString()).isEqualTo("[\"GET\",\"POST\"]");
        JsonNode properties = options.at("/schema/properties");
        assertThat(options.at("/schema/title").asText()).isEqualTo("wp_block");
        assertThat(properties.has("wp_pattern_sync_status")).isFalse();
        assertThat(fieldNames(properties.at("/meta/properties")))
                .containsExactly("wp_pattern_sync_status", "footnotes");
        assertThat(fieldNames(properties.at("/title/properties"))).containsExactly("raw");
        assertThat(properties.at("/title/properties/raw/context").toString()).isEqualTo("[\"view\",\"edit\"]");
        assertThat(fieldNames(properties.at("/content/properties")))
                .containsExactly("raw", "block_version", "protected");
        assertThat(properties.at("/content/properties/raw/context").toString()).isEqualTo("[\"view\",\"edit\"]");
        List<String> args = fieldNames(options.at("/endpoints/0/args"));
        assertThat(args).contains("wp_pattern_category", "wp_pattern_category_exclude", "tax_relation");
        assertThat(args).doesNotContain("author", "author_exclude", "parent", "parent_exclude", "menu_order", "sticky");
        assertThat(args).doesNotContain("format");
        assertThat(fieldNames(options.at("/endpoints/1/args")))
                .containsExactly(
                        "date",
                        "date_gmt",
                        "slug",
                        "status",
                        "password",
                        "title",
                        "content",
                        "excerpt",
                        "meta",
                        "template",
                        "wp_pattern_category");
    }
}
