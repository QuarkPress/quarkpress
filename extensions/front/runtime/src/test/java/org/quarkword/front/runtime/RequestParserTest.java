package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.front.QueryVars;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.runtime.Slugs;

/** WordPress 7.1.3's request parsing, recorded with PHP against its own parser for the reference's requests. */
class RequestParserTest {

    private static final String DEFAULT = "/%year%/%monthnum%/%day%/%postname%/";

    /** A page lookup over a fixed set of paths. */
    static final class FakePages extends PagePaths {

        private final Map<String, PostSnapshot> paths = new HashMap<>();

        FakePages page(String path, String status) {
            String slug = path.substring(path.lastIndexOf('/') + 1);
            paths.put(path, snapshot(paths.size() + 20, "page", status, slug, ""));
            return this;
        }

        FakePages post(String slug, String content) {
            paths.put("post:" + slug, snapshot(paths.size() + 20, "post", "publish", slug, content));
            return this;
        }

        @Override
        public Optional<PostSnapshot> find(String path, List<String> types) {
            String key = String.join("/", PagePaths.parts(path));
            if (types.contains("post")) {
                return Optional.ofNullable(paths.get("post:" + key));
            }
            return Optional.ofNullable(paths.get(key)).filter(page -> types.contains(page.type()));
        }

        @Override
        public ZoneId zone() {
            return ZoneOffset.UTC;
        }
    }

    static PostSnapshot snapshot(long id, String type, String status, String slug, String content) {
        Instant at = Instant.parse("2025-11-20T10:15:30Z");
        return new PostSnapshot(
                PostId.of(id),
                SiteId.of(1),
                type,
                status,
                slug,
                slug,
                content,
                "",
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.of(at),
                at,
                at,
                "open",
                "open",
                "",
                false,
                "",
                Map.of(),
                1);
    }

    private static final RequestParser.Registries REGISTRIES =
            new RequestParser.Registries(RewriteRulesTest.types(), RewriteRulesTest.taxonomies());

    private static QueryVars parse(String structure, String uri, PagePaths pages, String homePath) {
        Structure parsed = Structure.of(structure, "", "");
        RewriteRules rules =
                RewriteRules.of(parsed, REGISTRIES.postTypes(), REGISTRIES.taxonomies(), !homePath.isEmpty(), false);
        String path = uri;
        Map<String, List<String>> query = new HashMap<>();
        int at = uri.indexOf('?');
        if (at >= 0) {
            path = uri.substring(0, at);
            for (String pair : uri.substring(at + 1).split("&")) {
                int equals = pair.indexOf('=');
                String name = equals < 0 ? pair : pair.substring(0, equals);
                String value = equals < 0 ? "" : RewriteRules.phpUrldecode(pair.substring(equals + 1));
                query.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
        }
        return RequestParser.parse(path, query, parsed, rules, REGISTRIES, pages, homePath);
    }

    private static QueryVars parse(String structure, String uri) {
        return parse(structure, uri, new FakePages(), "");
    }

    private static final Map<String, String> HELLO =
            Map.of("year", "2025", "monthnum", "11", "day", "20", "name", "hello-world", "page", "");

    @Test
    void theHomePathIsStrippedIgnoringCase() {
        assertThat(parse(DEFAULT, "/Site2/about/", new FakePages(), "site2").asMap())
                .isEqualTo(Map.of("pagename", "about", "page", ""));
        assertThat(parse(DEFAULT, "/site2/2025/11/20/hello-world/", new FakePages(), "/site2/")
                        .asMap())
                .isEqualTo(HELLO);
        assertThat(parse(DEFAULT, "/site2/", new FakePages(), "site2")).isEqualTo(QueryVars.empty());
        assertThat(parse(DEFAULT, "/site2/index.php", new FakePages(), "site2")).isEqualTo(QueryVars.empty());
        assertThat(parse(DEFAULT, "/other/2025/11/20/hello-world/", new FakePages(), "site2")
                        .asMap())
                .isEqualTo(Map.of("pagename", "other/2025/11/20/hello-world", "page", ""));
    }

    @Test
    void indexPhpIsTheHomeAndAPathInfoPrefixIsDropped() {
        assertThat(parse(DEFAULT, "/index.php")).isEqualTo(QueryVars.empty());
        assertThat(parse(DEFAULT, "/")).isEqualTo(QueryVars.empty());
        assertThat(parse(DEFAULT, "/index.php/2025/11/20/hello-world/").asMap()).isEqualTo(HELLO);
    }

    @Test
    void theRewriteVariablesAreRecordedAsSuch() {
        QueryVars vars = parse(DEFAULT, "/2025/11/20/hello-world/");
        assertThat(vars.asMap()).isEqualTo(HELLO);
        assertThat(vars.fromRewrite()).containsExactlyInAnyOrder("year", "monthnum", "day", "name", "page");
        assertThat(vars.fromQueryString()).isEmpty();
        assertThat(vars.error404()).isFalse();
    }

    @Test
    void onlyPublicVariablesComeFromTheQueryString() {
        QueryVars vars = parse(DEFAULT, "/?p=1&posts_per_page=1&utm_source=x");
        assertThat(vars.asMap()).isEqualTo(Map.of("p", "1"));
        assertThat(vars.fromQueryString()).containsExactly("p");
        assertThat(parse(DEFAULT, "/?p=1&p=2").asMap()).isEqualTo(Map.of("p", "2"));
        assertThat(parse(DEFAULT, "/?p=1&P=2").asMap()).isEqualTo(Map.of("p", "1"));
        assertThat(parse(DEFAULT, "/?name=&p=&page_id=").asMap()).isEqualTo(Map.of("name", "", "p", "", "page_id", ""));
        assertThat(parse(DEFAULT, "/?page=/2/").asMap()).isEqualTo(Map.of("page", "/2/"));
        assertThat(parse(DEFAULT, "/?rest_route=/wp/v2/posts&sitemap-subtype=x&sitemap-stylesheet=y&post_format=aside")
                        .asMap())
                .isEqualTo(Map.of(
                        "rest_route",
                        "/wp/v2/posts",
                        "sitemap-subtype",
                        "x",
                        "sitemap-stylesheet",
                        "y",
                        "post_format",
                        "aside"));
    }

    @Test
    void aPostTypeQueryVariableSetsTheTypeAndTheName() {
        QueryVars vars = parse(DEFAULT, "/?book=dune");
        assertThat(vars.asMap()).isEqualTo(Map.of("book", "dune", "post_type", "book", "name", "dune"));
        assertThat(vars.fromQueryString()).containsExactlyInAnyOrder("book", "post_type", "name");
        assertThat(parse(DEFAULT, "/?book=dune&post_type=page").asMap())
                .isEqualTo(Map.of("book", "dune", "post_type", "book", "name", "dune"));
        assertThat(parse(DEFAULT, "/?name=other&book=dune").asMap())
                .isEqualTo(Map.of("book", "dune", "post_type", "book", "name", "dune"));
        QueryVars pretty = parse(DEFAULT, "/books/dune/");
        assertThat(pretty.asMap()).isEqualTo(Map.of("book", "dune", "post_type", "book", "name", "dune", "page", ""));
        assertThat(pretty.fromRewrite()).containsExactlyInAnyOrder("book", "post_type", "name", "page");
        assertThat(parse(DEFAULT, "/?wp_block=x")).isEqualTo(QueryVars.empty());
        assertThat(parse(DEFAULT, "/?Book=dune")).isEqualTo(QueryVars.empty());
        // an empty value, as PHP counts it, is not expanded
        assertThat(parse(DEFAULT, "/?book=").asMap()).isEqualTo(Map.of("book", ""));
        assertThat(parse(DEFAULT, "/?book=0").asMap()).isEqualTo(Map.of("book", "0"));
        assertThat(parse(DEFAULT, "/?book=00").asMap())
                .isEqualTo(Map.of("book", "00", "post_type", "book", "name", "00"));
        assertThat(parse(DEFAULT, "/books/0/").asMap()).isEqualTo(Map.of("book", "0", "page", ""));
    }

    @Test
    void postTypeMustBePubliclyQueryable() {
        assertThat(parse(DEFAULT, "/?post_type=page&page_id=20").asMap()).isEqualTo(Map.of("page_id", "20"));
        assertThat(parse(DEFAULT, "/?post_type=book&p=40").asMap()).isEqualTo(Map.of("post_type", "book", "p", "40"));
        assertThat(parse(DEFAULT, "/?post_type=attachment").asMap()).isEqualTo(Map.of("post_type", "attachment"));
        assertThat(parse(DEFAULT, "/?post_type=wp_block&p=1").asMap()).isEqualTo(Map.of("p", "1"));
        // WordPress drops "any" here: it is not a registered type
        assertThat(parse(DEFAULT, "/?post_type=any")).isEqualTo(QueryVars.empty());
        assertThat(parse(DEFAULT, "/?post_type=")).isEqualTo(QueryVars.empty());
    }

    @Test
    void taxonomyValuesKeepTheirPlusesAndHiddenTaxonomiesAreDropped() {
        assertThat(parse(DEFAULT, "/?tag=a+b").asMap()).isEqualTo(Map.of("tag", "a+b"));
        assertThat(parse(DEFAULT, "/?tag=a%20b").asMap()).isEqualTo(Map.of("tag", "a+b"));
        assertThat(parse(DEFAULT, "/?genre=a+b&tag=c+d&category_name=e+f&s=g+h").asMap())
                .isEqualTo(Map.of("genre", "a+b", "tag", "c+d", "category_name", "e+f", "s", "g h"));
        assertThat(parse(DEFAULT, "/tag/a+b/").asMap()).isEqualTo(Map.of("tag", "a+b"));
        assertThat(parse(DEFAULT, "/?taxonomy=wp_pattern_category&term=hero")).isEqualTo(QueryVars.empty());
        assertThat(parse(DEFAULT, "/?taxonomy=genre&term=scifi").asMap())
                .isEqualTo(Map.of("taxonomy", "genre", "term", "scifi"));
        assertThat(parse(DEFAULT, "/?taxonomy=nosuch&term=x").asMap())
                .isEqualTo(Map.of("taxonomy", "nosuch", "term", "x"));
        assertThat(parse(DEFAULT, "/?taxonomy=&term=x").asMap()).isEqualTo(Map.of("taxonomy", "", "term", "x"));
    }

    @Test
    void theQueryStringWinsOverTheRule() {
        QueryVars vars = parse(DEFAULT, "/2025/11/20/hello-world/?page=2");
        assertThat(vars.asMap())
                .isEqualTo(Map.of("year", "2025", "monthnum", "11", "day", "20", "name", "hello-world", "page", "2"));
        assertThat(vars.fromQueryString()).containsExactly("page");
        assertThat(vars.fromRewrite()).containsExactlyInAnyOrder("year", "monthnum", "day", "name");
        QueryVars tag = parse(DEFAULT, "/tag/java/?tag=php");
        assertThat(tag.asMap()).isEqualTo(Map.of("tag", "php"));
        assertThat(tag.fromQueryString()).containsExactly("tag");
        assertThat(tag.fromRewrite()).isEmpty();
        assertThat(parse(DEFAULT, "/2025/11/20/hello-world/?page=").asMap()).isEqualTo(HELLO);
    }

    @Test
    void errorFromTheQueryStringCountsOnlyInPlainMode() {
        // WordPress 7.1.3 (recorded with PHP and against the served site): with a rewrite table a query-string
        // "error" is dropped, in plain mode it is kept
        assertThat(parse(DEFAULT, "/?error=404")).isEqualTo(QueryVars.empty());
        assertThat(parse(DEFAULT, "/?p=1&error=404").asMap()).isEqualTo(Map.of("p", "1"));
        assertThat(parse(DEFAULT, "/a/b/c/d/x/?error=403").asMap())
                .isEqualTo(Map.of("pagename", "a/b/c/d/x", "page", ""));
        assertThat(parse(DEFAULT, "/wp-app.php/x").asMap()).isEqualTo(Map.of("error", "403"));
        assertThat(parse("", "/?error=404").asMap()).isEqualTo(Map.of("error", "404"));
        assertThat(parse("", "/?error=404").error404()).isTrue();
        assertThat(parse("", "/anything/?p=1&error=404").asMap()).isEqualTo(Map.of("p", "1", "error", "404"));
    }

    @Test
    void verbosePageRulesConsultThePages() {
        FakePages pages = new FakePages()
                .page("about", "publish")
                .page("about/team", "publish")
                .page("trashed-page", "trash")
                .page("draft-page", "draft");
        assertThat(parse("/%postname%/", "/about/team/", pages, "").asMap())
                .isEqualTo(Map.of("pagename", "about/team", "page", ""));
        assertThat(parse("/%postname%/", "/About/Team/", pages, "").asMap())
                .isEqualTo(Map.of("pagename", "About/Team", "page", ""));
        assertThat(parse("/%postname%/", "/draft-page/", pages, "").asMap())
                .isEqualTo(Map.of("pagename", "draft-page", "page", ""));
        assertThat(parse("/%postname%/", "/hello-world/", pages, "").asMap())
                .isEqualTo(Map.of("name", "hello-world", "page", ""));
        assertThat(parse("/%postname%/", "/hello-world/2/", pages, "").asMap())
                .isEqualTo(Map.of("name", "hello-world", "page", "2"));
        assertThat(parse("/%postname%/", "/hello-world/photo/", pages, "").asMap())
                .isEqualTo(Map.of("attachment", "photo"));
        assertThat(parse("/%postname%/", "/trashed-page/", pages, "").asMap())
                .isEqualTo(Map.of("name", "trashed-page", "page", ""));
        assertThat(parse("/%postname%/", "/no-such-thing/", pages, "").asMap())
                .isEqualTo(Map.of("name", "no-such-thing", "page", ""));
        QueryVars none = parse("/%postname%/", "/a/b/c/d/", pages, "");
        assertThat(none.asMap()).isEqualTo(Map.of("error", "404"));
        assertThat(none.error404()).isTrue();
        assertThat(parse("/%postname%/", "/wp-admin/x/y/z/", pages, "")).isEqualTo(QueryVars.empty());
    }

    @Test
    void withoutVerboseRulesThePageGroupAcceptsAnything() {
        assertThat(parse(DEFAULT, "/no-such-thing/").asMap())
                .isEqualTo(Map.of("pagename", "no-such-thing", "page", ""));
        assertThat(parse(DEFAULT, "/about/team/").asMap()).isEqualTo(Map.of("pagename", "about/team", "page", ""));
    }

    @Test
    void encodedPaths() {
        // a slug given as "café" is stored in its percent-encoded, lower-case form
        String stored = Slugs.sanitizeTitleForQuery("café");
        assertThat(stored).isEqualTo("caf%c3%a9");
        QueryVars upper = parse(DEFAULT, "/2025/11/20/caf%C3%A9/");
        assertThat(upper.get("name")).contains("caf%C3%A9");
        QueryVars lower = parse(DEFAULT, "/2025/11/20/caf%c3%a9/");
        assertThat(lower.get("name")).contains("caf%c3%a9");
        QueryVars raw = parse(DEFAULT, "/2025/11/20/café/");
        assertThat(raw.get("name")).contains("café");
        for (QueryVars vars : List.of(upper, lower, raw)) {
            assertThat(Slugs.sanitizeTitleForQuery(vars.get("name").orElseThrow()))
                    .isEqualTo(stored);
        }
        assertThat(parse(DEFAULT, "/2025/11/20/a%2Fb/").asMap())
                .isEqualTo(Map.of("year", "2025", "monthnum", "11", "day", "20", "name", "a%2Fb", "page", ""));
    }

    @Test
    void plainModeIgnoresThePath() {
        assertThat(parse("", "/anything/")).isEqualTo(QueryVars.empty());
        assertThat(parse("", "/anything/?p=1").asMap()).isEqualTo(Map.of("p", "1"));
        assertThat(parse("", "/2025/11/20/hello-world/")).isEqualTo(QueryVars.empty());
        assertThat(parse("", "/?p=1&page_id=2").asMap()).isEqualTo(Map.of("p", "1", "page_id", "2"));
    }

    @Test
    void numericSlugsAreToldFromDates() {
        FakePages pages = new FakePages().post("2024", "one");
        assertThat(parse("/%postname%/", "/2024/", pages, "").asMap()).isEqualTo(Map.of("name", "2024", "page", "0"));
        assertThat(parse("/%postname%/", "/2024/3/", pages, "").asMap())
                .isEqualTo(Map.of("year", "2024", "monthnum", "3"));
        assertThat(parse(DEFAULT, "/2024/", pages, "").asMap()).isEqualTo(Map.of("year", "2024"));
    }
}
