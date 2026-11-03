package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
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
import org.quarkpress.taxonomy.runtime.JpaTermConditions;

/**
 * The term and format filters of the posts collection, with WordPress's examples: category c3 with the children c7
 * and c8, category c4, the tags t4 and t5, two aside posts and two sticky ones. Each post's date is a day apart, so
 * the newest-first order is fixed.
 */
class TermFiltersDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            RestApp.network(TermFiltersDbTest.class, BookType.class, PostFixtures.class, PostFixtures.Builder.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String POSTS = "/wp-json/wp/v2/posts?per_page=100&_fields=id&";

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long carol;
    static long c3;
    static long c7;
    static long c8;
    static long c4;
    static long t4;
    static long t5;
    static long inC3;
    static long inC7;
    static long inC8;
    static long inC4;
    static long aside3;
    static long asideNone;
    static long plain;
    static long sticky3;
    static long stickyNone;
    static List<Long> all;

    @Inject
    JpaTermConditions provider;

    @BeforeAll
    static void fixture() {
        data = Jdbc.dataSource();
        site = TestData.siteIn(data, "UTC");
        host = host(site);
        UserId author = TestData.profile(data, "author");
        TestData.member(data, site, author, "[\"author\"]");
        carol = author.value();
        c3 = term(site, "category", "c3", null);
        c7 = term(site, "category", "c7", c3);
        c8 = term(site, "category", "c8", c3);
        c4 = term(site, "category", "c4", null);
        t4 = term(site, "post_tag", "t4", null);
        t5 = term(site, "post_tag", "t5", null);
        long aside = term(site, "post_format", "post-format-aside", null);
        inC3 = post("in c3", 1, false, c3);
        inC7 = post("in c7", 2, false, c7, t5);
        inC8 = post("in c8", 3, false, c8);
        inC4 = post("in c4", 4, false, c4, t4, t5);
        aside3 = post("aside in c3", 5, false, c3, aside);
        asideNone = post("aside", 6, false, aside);
        plain = post("plain", 7, false);
        sticky3 = post("sticky in c3", 8, true, c3);
        stickyNone = post("sticky", 9, true);
        all = List.of(stickyNone, sticky3, plain, asideNone, aside3, inC4, inC8, inC7, inC3);
    }

    @Test
    void wordPressExamples() {
        Response none = get("categories=999");
        none.then().statusCode(200).header("X-WP-Total", "0").header("X-WP-TotalPages", "0");
        assertThat(ids(none)).isEmpty();
        assertThat(ids(get("categories=" + c3 + ",999"))).containsExactly(sticky3, aside3, inC3);
        assertThat(ids(get("categories_exclude=999"))).containsExactlyElementsOf(all);
        assertThat(ids(get("categories[terms]=" + c3 + "&categories[include_children]=1")))
                .containsExactly(sticky3, aside3, inC8, inC7, inC3);
        // without include_children the children's posts are not in c3
        assertThat(ids(get("categories[terms]=" + c3))).containsExactly(sticky3, aside3, inC3);
        assertThat(ids(get("tags[terms]=" + t4 + "," + t5 + "&tags[operator]=AND")))
                .containsExactly(inC4);
        assertThat(ids(get("tags[terms]=" + t4 + "," + t5))).containsExactly(inC4, inC7);
        assertThat(ids(get("categories=" + c3 + "&tags=" + t5 + "&tax_relation=OR")))
                .containsExactly(sticky3, aside3, inC4, inC7, inC3);
        assertThat(ids(get("categories=" + c3 + "&tags=" + t5))).isEmpty();
        assertThat(ids(get("tags[terms]=" + t4 + ",999&tags[operator]=AND"))).isEmpty();
        // the format group is one more clause of the top-level query (G 16)
        assertThat(ids(get("categories=" + c4 + "&format=aside&tax_relation=OR")))
                .containsExactly(asideNone, aside3, inC4);
        assertThat(ids(get("categories=" + c3 + "&format=aside"))).containsExactly(aside3);
        assertThat(ids(get("format=standard"))).containsExactly(stickyNone, sticky3, plain, inC4, inC8, inC7, inC3);
        assertThat(ids(get("format=aside,standard"))).containsExactlyElementsOf(all);
        // excluding a tree
        assertThat(ids(get("categories_exclude[terms]=" + c3 + "&categories_exclude[include_children]=1")))
                .containsExactly(stickyNone, plain, asideNone, inC4);
        // zeros are dropped; the list forms of the query string (G 16)
        assertThat(ids(get("categories=0," + c3))).containsExactly(sticky3, aside3, inC3);
        assertThat(ids(get("categories[]=" + c3 + "&categories[]=" + c4))).containsExactly(sticky3, aside3, inC4, inC3);
        assertThat(ids(Http.at(host, null).urlEncodingEnabled(false).get(POSTS + "categories=" + c3 + "%20" + c4)))
                .containsExactly(sticky3, aside3, inC4, inC3);
        // the filters combine with the other ones
        assertThat(ids(get("categories=" + c3 + "&exclude=" + aside3))).containsExactly(sticky3, inC3);
    }

    @Test
    void refusalsFollowWordPress() {
        String both = "categories matches Term ID List and Term ID Taxonomy Query, but should match only one.";
        for (String query : List.of("categories=", "categories[terms]=a&categories=")) {
            refused(query, "categories")
                    .body("data.params.categories", equalTo(both))
                    .body("data.details.categories.code", equalTo("rest_one_of_multiple_matches"))
                    .body("data.details.categories.message", equalTo(both))
                    .body("data.details.categories.data.positions", equalTo(List.of(0, 1)));
        }
        String reason =
                "categories is not a valid Term ID Taxonomy Query. Reason: categories[terms][0] is not of type integer.";
        refused("categories=&categories[terms]=a", "categories")
                .body("data.params.categories", equalTo(reason))
                .body("data.details.categories.code", equalTo("rest_no_matching_schema"))
                .body("data.details.categories.message", equalTo(reason))
                .body("data.details.categories.data.position", equalTo(1));
        refused("categories=abc", "categories")
                .body(
                        "data.details.categories.message",
                        equalTo("categories is not a valid Term ID List. Reason: categories[0] is not of type"
                                + " integer."))
                .body("data.details.categories.data.position", equalTo(0));
        refused("categories[terms]=" + c3 + "&categories[bogus]=1", "categories")
                .body(
                        "data.params.categories",
                        equalTo(
                                "categories is not a valid Term ID Taxonomy Query. Reason: bogus is not a valid property"
                                        + " of Object."))
                .body("data.details.categories.data.position", equalTo(1));
        refused("tags[include_children]=1", "tags")
                .body("data.details.tags.code", equalTo("rest_no_matching_schema"))
                .body(
                        "data.params.tags",
                        equalTo("tags is not a valid Term ID Taxonomy Query. Reason: include_children is not a valid"
                                + " property of Object."));
        refused("categories_exclude[operator]=AND", "categories_exclude")
                .body(
                        "data.params.categories_exclude",
                        equalTo("categories_exclude is not a valid Term ID Taxonomy Query. Reason: operator is not a"
                                + " valid property of Object."));
        refused("tax_relation=XOR", "tax_relation")
                .body("data.params.tax_relation", equalTo("tax_relation is not one of AND and OR."))
                .body("data.details.tax_relation.code", equalTo("rest_not_in_enum"));
        // pages have no term filters: the parameters are not the endpoint's and are ignored
        as(null).get("/wp-json/wp/v2/pages?categories=&tax_relation=XOR").then().statusCode(200);
    }

    @Test
    void hugeListsAndDeepHierarchiesStayBounded() {
        // the lists go in a JSON body: a query string that long passes the server's request line limit
        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= 1000; i++) {
            ids.append(i == 1 ? c3 : 100_000_000L + i).append(',');
        }
        String list = ids.substring(0, ids.length() - 1);
        assertThat(ids(withBody("{\"categories\":[" + list + "]}"))).containsExactly(sticky3, aside3, inC3);
        assertThat(ids(withBody("{\"categories\":\"" + list + "\"}"))).containsExactly(sticky3, aside3, inC3);
        String tooMany = list + ",1";
        for (String body : List.of("{\"categories\":[" + tooMany + "]}", "{\"categories\":\"" + tooMany + "\"}")) {
            refused(withBody(body), "categories")
                    .body("data.params.categories", equalTo("categories must contain at most 1000 items."))
                    .body("data.details.categories.code", equalTo("rest_too_many_items"));
        }
        refused(withBody("{\"tags_exclude\":{\"terms\":[" + tooMany + "]}}"), "tags_exclude")
                .body("data.params.tags_exclude", equalTo("tags_exclude must contain at most 1000 items."));

        // 5000 categories in a 50-level tree on a site of its own: one root, 49 levels of about 102 terms, each
        // under a term of the level above; the expansion reads one level per query
        SiteId deep = TestData.siteIn(data, "UTC");
        long root = term(deep, "category", "deep-root", null);
        List<Long> level = List.of(root);
        int made = 1;
        for (int depth = 2; depth <= 50; depth++) {
            int size = depth == 50 ? 5000 - made : 102;
            level = insertLevel(deep, "deep-" + depth + "-", size, level);
            made += size;
        }
        assertThat(made).isEqualTo(5000);
        long deepest = PostFixtures.builder(deep)
                .author(carol)
                .title("deepest")
                .publishedAt("2026-03-01T10:00:00Z")
                .insert(data)
                .value();
        assign(deep, deepest, level.get(level.size() - 1));
        long started = System.nanoTime();
        Response expanded =
                Http.at(host(deep), null).get(POSTS + "categories[terms]=" + root + "&categories[include_children]=1");
        assertThat(ids(expanded)).containsExactly(deepest);
        long elapsed = System.nanoTime() - started;
        System.out.println("TermFiltersDbTest: 5000-term expansion answered in " + elapsed / 1_000_000 + " ms");
        assertThat(elapsed).isLessThan(5_000_000_000L);

        // an expansion past the taxonomy module's cap is refused as an invalid parameter
        provider.limitExpandedIds(2);
        try {
            String message = "The term filter expands to too many terms.";
            refused("categories_exclude[terms]=" + c3 + "&categories_exclude[include_children]=1", "categories_exclude")
                    .body("data.params.categories_exclude", equalTo(message))
                    .body("data.details.categories_exclude.code", equalTo("term_filter_too_large"))
                    .body("data.details.categories_exclude.message", equalTo(message));
            // the ids listed are not expanded, so they do not count against it
            assertThat(ids(get("categories=" + c3 + "," + c7 + "," + c8)))
                    .containsExactly(sticky3, aside3, inC8, inC7, inC3);
        } finally {
            provider.limitExpandedIds(JpaTermConditions.MAX_EXPANDED_IDS);
        }
    }

    @Test
    void aPositiveFilterTurnsStickyFirstOff() {
        // an include filter makes the list an archive: no sticky post first (G 6)
        Response archive =
                as(null).get("/wp-json/wp/v2/posts?ignore_sticky=false&per_page=2&_fields=id&categories=" + c3);
        archive.then().header("X-WP-Total", "3").header("X-WP-TotalPages", "2");
        assertThat(ids(archive)).containsExactly(sticky3, aside3);
        Response asides = as(null).get("/wp-json/wp/v2/posts?ignore_sticky=false&per_page=2&_fields=id&format=aside");
        assertThat(ids(asides)).containsExactly(asideNone, aside3);
        // an exclude filter does not: the sticky posts come first, the one in the excluded category too (G 6,
        // recorded), and the totals count the filtered set only
        Response excluded =
                as(null).get("/wp-json/wp/v2/posts?ignore_sticky=false&per_page=2&_fields=id&categories_exclude=" + c3);
        excluded.then().header("X-WP-Total", "6").header("X-WP-TotalPages", "3");
        assertThat(ids(excluded)).containsExactly(stickyNone, sticky3, plain);
        // format=standard is the posts without a format: not an archive either, so the two sticky posts of the page
        // stay first; sticky-first changes page 1 only
        Response standard =
                as(null).get("/wp-json/wp/v2/posts?ignore_sticky=false&per_page=2&_fields=id&format=standard");
        assertThat(ids(standard)).containsExactly(stickyNone, sticky3);
        Response later =
                as(null).get("/wp-json/wp/v2/posts?ignore_sticky=false&per_page=2&page=2&_fields=id&format=standard");
        assertThat(ids(later)).containsExactly(plain, inC4);
    }

    // ---- helpers

    private static io.restassured.response.ValidatableResponse refused(String query, String param) {
        return refused(get(query), param);
    }

    private static io.restassured.response.ValidatableResponse refused(Response response, String param) {
        return response.then()
                .statusCode(400)
                .header("Allow", "GET")
                .body("code", equalTo("rest_invalid_param"))
                .body("message", equalTo("Invalid parameter(s): " + param))
                .body("data.status", equalTo(400));
    }

    /** The posts collection with the parameters in a JSON body. */
    private static Response withBody(String json) {
        return as(null).contentType("application/json").body(json).get("/wp-json/wp/v2/posts?per_page=100&_fields=id");
    }

    private static Response get(String query) {
        return as(null).get(POSTS + query);
    }

    static RequestSpecification as(String bearer) {
        return Http.at(host, bearer);
    }

    private static String host(SiteId site) {
        return Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
    }

    private static long term(SiteId site, String taxonomy, String slug, Long parent) {
        Jdbc.update(
                data,
                "insert into qp_term (site_id, taxonomy, name, slug, parent_id, description) values (?, ?, ?, ?, ?, '')",
                site.value(),
                taxonomy,
                slug,
                slug,
                parent);
        return TestData.termId(data, site, taxonomy, slug);
    }

    private static void assign(SiteId site, long post, long term) {
        Jdbc.update(
                data,
                "insert into qp_term_assignment (site_id, post_id, term_id) values (?, ?, ?)",
                site.value(),
                post,
                term);
    }

    private static long post(String title, int day, boolean sticky, long... terms) {
        PostFixtures.Builder builder = PostFixtures.builder(site)
                .author(carol)
                .title(title)
                .publishedAt("2026-03-%02dT10:00:00Z".formatted(day))
                .modifiedAt("2026-03-%02dT10:00:00Z".formatted(day));
        if (sticky) {
            builder.sticky();
        }
        PostId post = builder.insert(data);
        for (long term : terms) {
            assign(site, post.value(), term);
        }
        return post.value();
    }

    /**
     * {@code size} categories under {@code parents} taken in turn, inserted by one multi-row statement; their ids
     * ascending.
     */
    private static List<Long> insertLevel(SiteId site, String prefix, int size, List<Long> parents) {
        StringBuilder sql = new StringBuilder(
                "insert into qp_term (site_id, taxonomy, name, slug, parent_id, description) values ");
        List<Object> params = new ArrayList<>(size * 4);
        for (int i = 0; i < size; i++) {
            sql.append(i == 0 ? "" : ", ").append("(?, 'category', ?, ?, ?, '')");
            params.add(site.value());
            params.add(prefix + i);
            params.add(prefix + i);
            params.add(parents.get(i % parents.size()));
        }
        Jdbc.update(data, sql.toString(), params.toArray());
        List<Long> ids = new ArrayList<>();
        for (String id : Jdbc.strings(
                data,
                "select id from qp_term where site_id = ? and slug like ? order by id",
                site.value(),
                prefix + "%")) {
            ids.add(Long.parseLong(id));
        }
        return ids;
    }

    static List<Long> ids(Response response) {
        response.then().statusCode(200);
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
}
