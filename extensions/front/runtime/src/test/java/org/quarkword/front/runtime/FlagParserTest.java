package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkword.core.SiteOptions;
import org.quarkword.front.FrontFlags;
import org.quarkword.front.QueriedObject;
import org.quarkword.front.QueryVars;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.TaxClause;
import org.quarkword.posts.TaxQuery;

/**
 * WordPress 7.1.3's {@code parse_query}, recorded with PHP against its own query object for the reference's examples
 * (parse-query.php / parse-query.out in the slice folder): flags, normalised variables, the queried page and the tax
 * query.
 */
class FlagParserTest {

    static final String DEFAULT = "/%year%/%monthnum%/%day%/%postname%/";
    static final FrontFlags NONE = FrontFlags.none();
    static final FrontFlags SINGLE = NONE.withSingle(true).withSingular(true);
    static final FrontFlags PAGE = NONE.withPage(true).withSingular(true);
    static final FrontFlags HOME = NONE.withHome(true);

    /** The reference's pages and attachments by path, and its posts by slug, with the reference's ids. */
    static final class FakePages extends PagePaths {

        private final Map<String, PostSnapshot> pages = new HashMap<>();
        private final Map<String, PostSnapshot> posts = new HashMap<>();

        FakePages page(long id, String path, String status) {
            pages.put(path, snapshot(id, "page", status, path.substring(path.lastIndexOf('/') + 1)));
            return this;
        }

        FakePages attachment(long id, String path) {
            pages.put(path, snapshot(id, "attachment", "inherit", path.substring(path.lastIndexOf('/') + 1)));
            return this;
        }

        FakePages post(long id, String slug) {
            posts.put(slug, snapshot(id, "post", "publish", slug));
            return this;
        }

        @Override
        public Optional<PostSnapshot> find(String path, List<String> types) {
            String key = String.join("/", PagePaths.parts(path));
            if (key.isEmpty() || key.equals("0")) {
                return Optional.empty();
            }
            if (types.contains("post") && posts.containsKey(key)) {
                return Optional.of(posts.get(key));
            }
            return Optional.ofNullable(pages.get(key)).filter(page -> types.contains(page.type()));
        }

        @Override
        public ZoneId zone() {
            return ZoneOffset.UTC;
        }
    }

    static PostSnapshot snapshot(long id, String type, String status, String slug) {
        return RequestParserTest.snapshot(id, type, status, slug, "");
    }

    /** The reference's cast: pages 20-25, attachments 30-31, posts 1, 3 and 10. */
    static FakePages cast() {
        return new FakePages()
                .page(20, "about", "publish")
                .page(23, "about/team", "publish")
                .page(21, "blog", "publish")
                .page(22, "home", "publish")
                .page(24, "privacy-policy", "publish")
                .page(25, "hidden", "draft")
                .attachment(30, "red-car-for-sale/photo")
                .attachment(31, "logo")
                .post(1, "hello-world")
                .post(3, "red-car-for-sale")
                .post(10, "long-story");
    }

    /** Site options holding {@code values} as texts. */
    static SiteOptions options(Map<String, String> values) {
        return new SiteOptions() {
            @Override
            public Optional<JsonNode> get(String name) {
                return Optional.ofNullable(values.get(name)).map(TextNode::valueOf);
            }

            @Override
            public String string(String name, String fallback) {
                return values.getOrDefault(name, fallback);
            }

            @Override
            public long number(String name, long fallback) {
                return Optional.ofNullable(values.get(name))
                        .map(Long::parseLong)
                        .orElse(fallback);
            }

            @Override
            public boolean flag(String name, boolean fallback) {
                String value = values.get(name);
                return value == null ? fallback : !(value.isEmpty() || value.equals("0"));
            }
        };
    }

    static final SiteOptions BLOG_FRONT = options(Map.of("show_on_front", "posts", "wp_page_for_privacy_policy", "24"));
    static final SiteOptions STATIC_FRONT = options(Map.of(
            "show_on_front",
            "page",
            "page_on_front",
            "22",
            "page_for_posts",
            "21",
            "wp_page_for_privacy_policy",
            "24"));

    static final RequestParser.Registries REGISTRIES =
            new RequestParser.Registries(RewriteRulesTest.types(), RewriteRulesTest.taxonomies());

    /** The variables of a query string: the last value of a repeated name, PHP's decoding of the values. */
    static QueryVars vars(String query) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (!query.isEmpty()) {
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                String name = equals < 0 ? pair : pair.substring(0, equals);
                String value = equals < 0 ? "" : RewriteRules.phpUrldecode(pair.substring(equals + 1));
                vars.put(name, value);
            }
        }
        return QueryVars.of(vars);
    }

    static FlagParser.Parsed parse(String query, SiteOptions options, String structure, PagePaths pages) {
        return FlagParser.parse(vars(query), options, REGISTRIES, pages, Structure.of(structure, "", ""));
    }

    static FlagParser.Parsed parse(String query, SiteOptions options) {
        return parse(query, options, DEFAULT, cast());
    }

    static FlagParser.Parsed parse(String query) {
        return parse(query, BLOG_FRONT);
    }

    static FrontFlags flags(String query) {
        return parse(query).flags();
    }

    static FrontFlags staticFront(String query) {
        return parse(query, STATIC_FRONT).flags();
    }

    static Optional<Long> queried(FlagParser.Parsed parsed) {
        return parsed.queriedFromPath().map(QueriedObject::id);
    }

    static TaxClause terms(String taxonomy, TaxClause.Operator operator, long... ids) {
        List<Long> list = new ArrayList<>();
        for (long id : ids) {
            list.add(id);
        }
        return new TaxClause.Terms(taxonomy, list, true, operator);
    }

    static TaxClause slugs(String taxonomy, String... slugs) {
        return new TaxClause.Slugs(taxonomy, List.of(slugs));
    }

    static TaxQuery tax(TaxClause... clauses) {
        return new TaxQuery(TaxClause.Relation.AND, List.of(clauses));
    }

    // ---- FQ1 normalisation --------------------------------------------------------------------------------------

    @Test
    void valuesAreNormalisedAsWordPressDoes() {
        assertThat(parse("p=10abc").normalized().asMap()).isEqualTo(Map.of("p", "10"));
        assertThat(parse("p=-3").normalized().asMap()).isEqualTo(Map.of("p", "0", "error", "404"));
        assertThat(flags("p=-3")).isEqualTo(NONE.withIs404(true));
        assertThat(flags("p=0")).isEqualTo(HOME);
        assertThat(flags("p=abc")).isEqualTo(HOME);
        assertThat(parse("m=2026-05").normalized().get("m")).contains("202605");
        assertThat(parse("m=2026052008xx").normalized().get("m")).contains("2026052008");
        assertThat(parse("cat=a3b").normalized().get("cat")).contains("3");
        assertThat(flags("author=carol")).isEqualTo(HOME);
        assertThat(parse("author=3x").normalized().get("author")).contains("3");
        assertThat(parse("pagename=%20about%20").normalized().get("pagename")).contains("about");
        assertThat(parse("name=%20hello-world%20").normalized().get("name")).contains("hello-world");
        assertThat(parse("subpost=photo").normalized().get("attachment")).contains("photo");
        assertThat(flags("subpost=photo")).isEqualTo(SINGLE.withAttachment(true));
        assertThat(parse("subpost_id=-31").normalized().get("attachment_id")).contains("31");
        assertThat(flags("subpost_id=31")).isEqualTo(SINGLE.withAttachment(true));
        assertThat(parse("year=-2026").normalized().get("year")).contains("2026");
        assertThat(parse("page_id=-20").normalized().get("page_id")).contains("20");
        assertThat(parse("paged=-2").normalized().get("paged")).contains("2");
        assertThat(flags("paged=-2")).isEqualTo(HOME.withPaged(true));
        assertThat(parse("hour=-3").normalized().get("hour")).contains("3");
        assertThat(parse("hour=").normalized().get("hour")).contains("");
        assertThat(flags("hour=")).isEqualTo(HOME);
    }

    @Test
    void robotsFaviconAndSitemapAreExclusiveAndDoNotStopTheParse() {
        assertThat(flags("robots=1")).isEqualTo(NONE.withRobots(true));
        assertThat(flags("robots=1&p=10")).isEqualTo(SINGLE.withRobots(true));
        assertThat(flags("favicon=1")).isEqualTo(NONE.withFavicon(true));
        assertThat(flags("sitemap=index")).isEqualTo(NONE.withSitemap(true));
        assertThat(flags("robots=1&favicon=1")).isEqualTo(NONE.withRobots(true));
        assertThat(flags("robots=0")).isEqualTo(HOME);
        assertThat(flags("sitemap=index&p=10&error=404")).isEqualTo(NONE.withIs404(true));
    }

    // ---- FQ2 the singular ladder -----------------------------------------------------------------------------------

    @Test
    void theSingularLadder() {
        assertThat(flags("p=10")).isEqualTo(SINGLE);
        assertThat(flags("page_id=20")).isEqualTo(PAGE);
        assertThat(flags("name=hello-world")).isEqualTo(SINGLE);
        assertThat(flags("attachment=photo")).isEqualTo(SINGLE.withAttachment(true));
        assertThat(flags("attachment_id=31")).isEqualTo(SINGLE.withAttachment(true));
        assertThat(flags("p=10&page_id=20")).isEqualTo(SINGLE);
        assertThat(flags("name=about&pagename=about")).isEqualTo(SINGLE);
        assertThat(queried(parse("name=about&pagename=about"))).contains(20L);
        assertThat(flags("pagename=about&cat=3")).isEqualTo(PAGE);
        assertThat(flags("name=hello-world&s=foo")).isEqualTo(SINGLE);
        assertThat(flags("attachment=photo&p=10")).isEqualTo(SINGLE.withAttachment(true));
        assertThat(flags("p=10&cat=3&tag=cars")).isEqualTo(SINGLE);
    }

    @Test
    void aSingularRequestHasNoTaxQuery() {
        assertThat(parse("pagename=about&cat=3").taxQuery()).isEqualTo(TaxQuery.NONE);
        assertThat(parse("p=10&category_name=news").taxQuery()).isEqualTo(TaxQuery.NONE);
        assertThat(parse("p=10&taxonomy=genre&term=scifi").taxQuery()).isEqualTo(TaxQuery.NONE);
        assertThat(parse("name=x&cat=3&tag=cars").taxQuery()).isEqualTo(TaxQuery.NONE);
    }

    // ---- FQ3 the archive branch ----------------------------------------------------------------------------------

    @Test
    void searchIsTheKeyBeingPresent() {
        assertThat(flags("s=red")).isEqualTo(NONE.withSearch(true));
        assertThat(flags("s=")).isEqualTo(NONE.withSearch(true));
        assertThat(flags("s=red&cat=3"))
                .isEqualTo(NONE.withSearch(true).withCategory(true).withArchive(true));
        assertThat(flags("s=red&year=2026&author=3"))
                .isEqualTo(NONE.withSearch(true)
                        .withYear(true)
                        .withDate(true)
                        .withAuthor(true)
                        .withArchive(true));
    }

    @Test
    void dates() {
        FrontFlags date = NONE.withDate(true).withArchive(true);
        assertThat(flags("year=2026")).isEqualTo(date.withYear(true));
        assertThat(flags("year=2026&monthnum=5")).isEqualTo(date.withMonth(true));
        assertThat(flags("year=2026&monthnum=5&day=20")).isEqualTo(date.withDay(true));
        assertThat(flags("year=2026&monthnum=2&day=30")).isEqualTo(NONE.withIs404(true));
        assertThat(parse("year=2026&monthnum=2&day=30").normalized().error404()).isTrue();
        assertThat(flags("year=2024&monthnum=2&day=29")).isEqualTo(date.withDay(true));
        assertThat(flags("monthnum=13")).isEqualTo(NONE.withIs404(true));
        assertThat(flags("monthnum=12")).isEqualTo(date.withMonth(true));
        assertThat(flags("day=40")).isEqualTo(date.withDay(true));
        assertThat(flags("day=31&monthnum=2")).isEqualTo(date.withDay(true));
        assertThat(flags("year=0&monthnum=2&day=30")).isEqualTo(date.withDay(true));
        assertThat(flags("year=2026&monthnum=13&day=1")).isEqualTo(NONE.withIs404(true));
        assertThat(flags("year=32767&monthnum=12&day=31")).isEqualTo(date.withDay(true));
        assertThat(flags("year=32768&monthnum=1&day=1")).isEqualTo(NONE.withIs404(true));
        assertThat(flags("m=2026")).isEqualTo(date.withYear(true));
        assertThat(flags("m=202605")).isEqualTo(date.withMonth(true));
        assertThat(flags("m=20260520")).isEqualTo(date.withDay(true));
        assertThat(flags("m=2026052008")).isEqualTo(date.withTime(true));
        assertThat(flags("m=20265")).isEqualTo(date.withYear(true));
        assertThat(flags("m=202613")).isEqualTo(date.withMonth(true));
        assertThat(flags("m=abc")).isEqualTo(HOME);
        assertThat(flags("w=21")).isEqualTo(date);
        assertThat(flags("w=21&year=2026")).isEqualTo(date.withYear(true));
        assertThat(flags("w=0")).isEqualTo(HOME);
        assertThat(flags("hour=8")).isEqualTo(date.withTime(true));
        assertThat(flags("hour=0")).isEqualTo(date.withTime(true));
        assertThat(flags("hour=abc")).isEqualTo(date.withTime(true));
        assertThat(flags("minute=5")).isEqualTo(date.withTime(true));
        assertThat(flags("second=0&year=2026")).isEqualTo(date.withTime(true));
        assertThat(flags("hour=8&day=20")).isEqualTo(date.withTime(true));
        assertThat(flags("hour=8&m=2026")).isEqualTo(date.withTime(true).withYear(true));
    }

    @Test
    void taxonomyFlagsComeFromThePositiveClauses() {
        FrontFlags category = NONE.withCategory(true).withArchive(true);
        FrontFlags tag = NONE.withTag(true).withArchive(true);
        FrontFlags tax = NONE.withTax(true).withArchive(true);
        assertThat(flags("cat=3")).isEqualTo(category);
        assertThat(flags("cat=-3")).isEqualTo(HOME);
        assertThat(flags("cat=3,-4")).isEqualTo(category);
        assertThat(flags("cat=0")).isEqualTo(HOME);
        assertThat(flags("cat=abc")).isEqualTo(HOME);
        assertThat(flags("category_name=news/europe")).isEqualTo(category);
        assertThat(flags("tag=cars")).isEqualTo(tag);
        assertThat(flags("tag=cars+paint")).isEqualTo(tag);
        assertThat(flags("tag=cars&cat=3")).isEqualTo(category.withTag(true));
        assertThat(flags("cat=-3&tag=cars")).isEqualTo(tag);
        assertThat(flags("taxonomy=post_format&term=post-format-aside")).isEqualTo(tax);
        assertThat(flags("taxonomy=genre&term=scifi")).isEqualTo(tax);
        assertThat(flags("taxonomy=nosuch&term=x")).isEqualTo(tax);
        assertThat(flags("taxonomy=genre")).isEqualTo(HOME);
        assertThat(flags("term=x")).isEqualTo(HOME);
        assertThat(flags("genre=scifi")).isEqualTo(tax);
        assertThat(flags("post_format=post-format-aside")).isEqualTo(tax);
    }

    @Test
    void authorsAndPostTypeArchives() {
        FrontFlags author = NONE.withAuthor(true).withArchive(true);
        assertThat(flags("author=3")).isEqualTo(author);
        assertThat(flags("author_name=carol")).isEqualTo(author);
        assertThat(flags("author=0")).isEqualTo(HOME);
        assertThat(flags("author=-2")).isEqualTo(author);
        assertThat(flags("author=0,3")).isEqualTo(author);
        // "0" is compared as a number
        assertThat(flags("author=00")).isEqualTo(HOME);
        assertThat(flags("author=-0")).isEqualTo(HOME);
        assertThat(flags("author=0.0")).isEqualTo(HOME);
        assertThat(flags("author=%200")).isEqualTo(HOME);
        assertThat(flags("author=0,0")).isEqualTo(author);
        assertThat(flags("author=00,3")).isEqualTo(author);
        FrontFlags typeArchive = NONE.withPostTypeArchive(true).withArchive(true);
        assertThat(flags("post_type=page")).isEqualTo(HOME);
        assertThat(flags("post_type=book")).isEqualTo(typeArchive);
        assertThat(flags("post_type=book&year=2026"))
                .isEqualTo(typeArchive.withYear(true).withDate(true));
        assertThat(flags("post_type=book&s=x")).isEqualTo(typeArchive.withSearch(true));
        assertThat(flags("post_type=any")).isEqualTo(HOME);
        assertThat(flags("post_type=post")).isEqualTo(HOME);
        assertThat(flags("post_type=nosuch")).isEqualTo(HOME);
        // the archive check reads the value before it is key-sanitised
        assertThat(flags("post_type=Book")).isEqualTo(HOME);
        assertThat(flags("post_type=book%20")).isEqualTo(HOME);
    }

    // ---- FQ4 feed, embed, trackback, paged, preview, comment feed ---------------------------------------------------

    @Test
    void feedsEmbedsTrackbacksPagingAndPreviews() {
        assertThat(flags("feed=rss2")).isEqualTo(NONE.withFeed(true));
        assertThat(flags("p=10&feed=rss2")).isEqualTo(SINGLE.withFeed(true).withCommentFeed(true));
        assertThat(flags("feed=comments-rss2")).isEqualTo(NONE.withFeed(true).withCommentFeed(true));
        assertThat(parse("feed=comments-rss2").normalized().asMap())
                .isEqualTo(Map.of("feed", "rss2", "withcomments", "1"));
        assertThat(parse("feed=xcomments-rss2").normalized().get("feed")).contains("xrss2");
        assertThat(flags("feed=comments-")).isEqualTo(NONE.withFeed(true).withCommentFeed(true));
        assertThat(flags("feed=rss2&withcomments=1"))
                .isEqualTo(NONE.withFeed(true).withCommentFeed(true));
        assertThat(flags("feed=rss2&withoutcomments=1&p=10")).isEqualTo(SINGLE.withFeed(true));
        assertThat(flags("feed=rss2&withcomments=1&withoutcomments=1"))
                .isEqualTo(NONE.withFeed(true).withCommentFeed(true));
        assertThat(flags("pagename=about&feed=rss2"))
                .isEqualTo(PAGE.withFeed(true).withCommentFeed(true));
        assertThat(flags("cat=3&feed=rss2"))
                .isEqualTo(NONE.withCategory(true).withArchive(true).withFeed(true));
        assertThat(flags("feed=rss2&paged=2")).isEqualTo(NONE.withFeed(true).withPaged(true));
        assertThat(flags("paged=2")).isEqualTo(HOME.withPaged(true));
        assertThat(flags("paged=1")).isEqualTo(HOME);
        assertThat(flags("paged=0")).isEqualTo(HOME);
        assertThat(flags("p=10&preview=true")).isEqualTo(SINGLE.withPreview(true));
        assertThat(flags("p=10&preview=x")).isEqualTo(SINGLE.withPreview(true));
        assertThat(flags("preview=true")).isEqualTo(HOME.withPreview(true));
        assertThat(flags("embed=true&cat=3")).isEqualTo(NONE.withCategory(true).withArchive(true));
        assertThat(flags("embed=true&p=10")).isEqualTo(SINGLE.withEmbed(true));
        assertThat(flags("embed=true")).isEqualTo(HOME);
        assertThat(flags("embed=true&error=404")).isEqualTo(NONE.withIs404(true).withEmbed(true));
        assertThat(flags("tb=1")).isEqualTo(NONE.withTrackback(true));
        assertThat(flags("p=10&tb=1")).isEqualTo(SINGLE.withTrackback(true));
    }

    // ---- FQ5 home and the static front page ------------------------------------------------------------------------

    @Test
    void homeWhenThePostsAreTheFront() {
        assertThat(flags("")).isEqualTo(HOME);
        assertThat(flags("paged=2")).isEqualTo(HOME.withPaged(true));
        assertThat(flags("page_id=22")).isEqualTo(PAGE);
        assertThat(flags("pagename=blog")).isEqualTo(PAGE);
        assertThat(queried(parse("pagename=blog"))).contains(21L);
        assertThat(flags("cpage=2")).isEqualTo(HOME);
        assertThat(flags("page=2")).isEqualTo(HOME);
        assertThat(flags("post_type=page")).isEqualTo(HOME);
        assertThat(flags("error=404")).isEqualTo(NONE.withIs404(true));
        assertThat(flags("").isFrontPage(BLOG_FRONT, Optional.empty())).isTrue();
        assertThat(flags("paged=2").isFrontPage(BLOG_FRONT, Optional.empty())).isTrue();
        assertThat(flags("page_id=22")
                        .isFrontPage(
                                BLOG_FRONT,
                                Optional.of(new QueriedObject.Post(snapshot(22, "page", "publish", "home")))))
                .isFalse();
    }

    @Test
    void theStaticFrontPageCorrection() {
        FlagParser.Parsed none = parse("", STATIC_FRONT);
        assertThat(none.flags()).isEqualTo(PAGE);
        assertThat(none.normalized().asMap()).isEqualTo(Map.of("page_id", "22"));
        assertThat(none.queriedFromPath()).isEmpty();
        QueriedObject home = new QueriedObject.Post(snapshot(22, "page", "publish", "home"));
        assertThat(none.flags().isFrontPage(STATIC_FRONT, Optional.of(home))).isTrue();
        assertThat(none.flags().isFrontPage(STATIC_FRONT, Optional.empty())).isFalse();
        FlagParser.Parsed paged = parse("paged=3", STATIC_FRONT);
        assertThat(paged.flags()).isEqualTo(PAGE.withPaged(true));
        assertThat(paged.normalized().asMap()).isEqualTo(Map.of("page_id", "22", "page", "3"));
        assertThat(parse("paged=1", STATIC_FRONT).normalized().asMap()).isEqualTo(Map.of("page_id", "22", "page", "1"));
        assertThat(staticFront("paged=1")).isEqualTo(PAGE);
        assertThat(parse("paged=0", STATIC_FRONT).normalized().asMap())
                .isEqualTo(Map.of("page_id", "22", "paged", "0"));
        assertThat(staticFront("preview=true")).isEqualTo(PAGE.withPreview(true));
        assertThat(staticFront("feed=rss2")).isEqualTo(NONE.withFeed(true));
        assertThat(staticFront("page_id=22")).isEqualTo(PAGE);
        assertThat(staticFront("pagename=home")).isEqualTo(PAGE);
        assertThat(parse("pagename=home", STATIC_FRONT)
                        .flags()
                        .isFrontPage(
                                STATIC_FRONT,
                                parse("pagename=home", STATIC_FRONT).queriedFromPath()))
                .isTrue();
        assertThat(parse("pagename=", STATIC_FRONT).normalized().asMap())
                .isEqualTo(Map.of("page_id", "22", "pagename", ""));
        assertThat(staticFront("pagename=")).isEqualTo(PAGE);
        assertThat(staticFront("embed=true")).isEqualTo(PAGE.withEmbed(true));
        assertThat(staticFront("embed=true&paged=2"))
                .isEqualTo(PAGE.withEmbed(true).withPaged(true));
        assertThat(staticFront("cpage=2")).isEqualTo(PAGE);
        assertThat(parse("page=2", STATIC_FRONT).normalized().asMap()).isEqualTo(Map.of("page_id", "22", "page", "2"));
        assertThat(parse("page=2&paged=3", STATIC_FRONT).normalized().asMap())
                .isEqualTo(Map.of("page_id", "22", "page", "3"));
        assertThat(parse("paged=3&preview=true&cpage=4&page=5", STATIC_FRONT)
                        .normalized()
                        .asMap())
                .isEqualTo(Map.of("page_id", "22", "page", "3", "preview", "true", "cpage", "4"));
        assertThat(staticFront("paged=3&preview=true&cpage=4&page=5"))
                .isEqualTo(PAGE.withPaged(true).withPreview(true));
        assertThat(staticFront("order=asc")).isEqualTo(HOME);
        assertThat(staticFront("post_type=page")).isEqualTo(HOME);
        assertThat(staticFront("cat=-3")).isEqualTo(HOME);
        assertThat(staticFront("error=404")).isEqualTo(NONE.withIs404(true));
        assertThat(staticFront("p=10&paged=2")).isEqualTo(SINGLE.withPaged(true));
        assertThat(staticFront("page_id=22&paged=2")).isEqualTo(PAGE.withPaged(true));
        assertThat(parse("page_id=22&paged=2", STATIC_FRONT).normalized().asMap())
                .isEqualTo(Map.of("page_id", "22", "paged", "2"));
        assertThat(staticFront("robots=1")).isEqualTo(NONE.withRobots(true));
        assertThat(staticFront("s=")).isEqualTo(NONE.withSearch(true));
        // any other key, even with an empty or zero value, keeps the request a home-like list
        assertThat(staticFront("page_id=0")).isEqualTo(HOME);
        assertThat(staticFront("name=")).isEqualTo(HOME);
        assertThat(staticFront("p=abc")).isEqualTo(HOME);
        assertThat(staticFront("foo=bar")).isEqualTo(HOME);
        assertThat(staticFront("paged=abc")).isEqualTo(PAGE);
        assertThat(staticFront("cpage=abc")).isEqualTo(PAGE);
        assertThat(staticFront("preview=0")).isEqualTo(PAGE.withPreview(true));
    }

    @Test
    void noFrontPageWhenThePageOnFrontIsZero() {
        SiteOptions options = options(Map.of("show_on_front", "page", "page_on_front", "0", "page_for_posts", "21"));
        assertThat(parse("", options).flags()).isEqualTo(HOME);
        assertThat(parse("paged=2", options).flags()).isEqualTo(HOME.withPaged(true));
        assertThat(parse("pagename=blog", options).flags()).isEqualTo(HOME.withPostsPage(true));
        assertThat(parse("page_id=21", options).flags()).isEqualTo(HOME.withPostsPage(true));
    }

    // ---- FQ6 pagename resolution ---------------------------------------------------------------------------------

    @Test
    void pagenameResolvesThePageByItsPath() {
        assertThat(flags("pagename=about")).isEqualTo(PAGE);
        assertThat(queried(parse("pagename=about"))).contains(20L);
        assertThat(queried(parse("pagename=about/team"))).contains(23L);
        assertThat(queried(parse("pagename=About/Team"))).contains(23L);
        assertThat(queried(parse("pagename=about/"))).contains(20L);
        assertThat(flags("pagename=team")).isEqualTo(PAGE);
        assertThat(queried(parse("pagename=team"))).isEmpty();
        assertThat(queried(parse("pagename=ABOUT"))).contains(20L);
        assertThat(queried(parse("pagename=hello-world"))).isEmpty();
        assertThat(queried(parse("pagename=nosuch"))).isEmpty();
        assertThat(queried(parse("pagename=0"))).isEmpty();
        assertThat(queried(parse("pagename=about&page_id=23"))).contains(20L);
        assertThat(queried(parse("page_id=23"))).isEmpty();
        assertThat(flags("pagename=hidden")).isEqualTo(PAGE);
        assertThat(queried(parse("pagename=hidden"))).contains(25L);
        assertThat(parse("pagename=about").queriedFromPath().orElseThrow()).isInstanceOf(QueriedObject.Post.class);
        assertThat(flags("pagename=about&error=404")).isEqualTo(NONE.withIs404(true));
        assertThat(queried(parse("pagename=about&error=404"))).contains(20L);
        assertThat(parse("pagename=about").normalized().get("pagename")).contains("about");
    }

    @Test
    void anAttachmentByPathIsAPageAtParseTime() {
        // the attachment flag comes with the fetch (WordPress sets it from the fetched post, not in parse_query)
        FlagParser.Parsed logo = parse("pagename=logo");
        assertThat(logo.flags()).isEqualTo(PAGE);
        assertThat(queried(logo)).contains(31L);
        assertThat(queried(parse("pagename=red-car-for-sale/photo"))).contains(30L);
        assertThat(queried(parse("pagename=photo"))).isEmpty();
        assertThat(flags("pagename=logo&embed=true")).isEqualTo(PAGE.withEmbed(true));
    }

    @Test
    void aPostSharingAnAttachmentsPathWinsUnderAPostnameFirstStructure() {
        FakePages withPost = cast().post(12, "logo");
        FlagParser.Parsed postname = parse("pagename=logo", BLOG_FRONT, "/%postname%/", withPost);
        assertThat(postname.flags()).isEqualTo(SINGLE);
        assertThat(queried(postname)).contains(12L);
        assertThat(postname.normalized().asMap()).isEqualTo(Map.of("pagename", "logo"));
        assertThat(parse("pagename=logo&embed=true", BLOG_FRONT, "/%postname%/", withPost)
                        .flags())
                .isEqualTo(SINGLE.withEmbed(true));
        assertThat(queried(parse("pagename=about", BLOG_FRONT, "/%postname%/", withPost)))
                .contains(20L);
        assertThat(parse("pagename=about", BLOG_FRONT, "/%postname%/", withPost).flags())
                .isEqualTo(PAGE);
        // the second lookup includes attachments, so the attachment itself is found again and the request is single
        FlagParser.Parsed noPost = parse("pagename=logo", BLOG_FRONT, "/%postname%/", cast());
        assertThat(noPost.flags()).isEqualTo(SINGLE);
        assertThat(queried(noPost)).contains(31L);
        assertThat(parse("pagename=red-car-for-sale/photo", BLOG_FRONT, "/%postname%/", cast())
                        .flags())
                .isEqualTo(SINGLE);
        FlagParser.Parsed notFirst = parse("pagename=logo", BLOG_FRONT, "/%category%/%postname%/", withPost);
        assertThat(notFirst.flags()).isEqualTo(PAGE);
        assertThat(queried(notFirst)).contains(31L);
        FlagParser.Parsed plain = parse("pagename=logo", BLOG_FRONT, "", withPost);
        assertThat(plain.flags()).isEqualTo(PAGE);
        assertThat(queried(plain)).contains(31L);
    }

    @Test
    void thePostsPage() {
        FrontFlags postsPage = HOME.withPostsPage(true);
        FlagParser.Parsed blog = parse("pagename=blog", STATIC_FRONT);
        assertThat(blog.flags()).isEqualTo(postsPage);
        assertThat(blog.queriedFromPath()).containsInstanceOf(QueriedObject.PostsPage.class);
        assertThat(queried(blog)).contains(21L);
        assertThat(blog.flags().isFrontPage(STATIC_FRONT, blog.queriedFromPath()))
                .isFalse();
        FlagParser.Parsed byId = parse("page_id=21", STATIC_FRONT);
        assertThat(byId.flags()).isEqualTo(postsPage);
        assertThat(byId.queriedFromPath()).isEmpty();
        assertThat(staticFront("pagename=blog&paged=2")).isEqualTo(postsPage.withPaged(true));
        assertThat(parse("pagename=blog&paged=2", STATIC_FRONT).normalized().asMap())
                .isEqualTo(Map.of("pagename", "blog", "paged", "2"));
        assertThat(staticFront("pagename=blog&feed=rss2")).isEqualTo(postsPage.withFeed(true));
        assertThat(staticFront("pagename=blog&feed=rss2&withcomments=1"))
                .isEqualTo(postsPage.withFeed(true).withCommentFeed(true));
        assertThat(staticFront("pagename=blog&feed=comments-rss2"))
                .isEqualTo(postsPage.withFeed(true).withCommentFeed(true));
        assertThat(staticFront("page_id=21&feed=rss2")).isEqualTo(postsPage.withFeed(true));
        // the reset stays unless withcomments is truthy
        assertThat(staticFront("pagename=blog&feed=rss2&withcomments=0")).isEqualTo(postsPage.withFeed(true));
        assertThat(staticFront("page_id=21&feed=rss2&withcomments=0")).isEqualTo(postsPage.withFeed(true));
        assertThat(staticFront("pagename=blog&feed=rss2&withcomments=")).isEqualTo(postsPage.withFeed(true));
        assertThat(staticFront("pagename=blog&embed=true")).isEqualTo(postsPage);
        assertThat(staticFront("pagename=blog&cat=3")).isEqualTo(postsPage);
        assertThat(staticFront("pagename=blog&page_id=22")).isEqualTo(postsPage);
        assertThat(parse("pagename=blog", STATIC_FRONT).taxQuery()).isEqualTo(TaxQuery.NONE);
        // with the posts shown on the front, the same page is an ordinary page
        assertThat(flags("pagename=blog")).isEqualTo(PAGE);
        assertThat(flags("page_id=21")).isEqualTo(PAGE);
        SiteOptions stored = options(Map.of("show_on_front", "posts", "page_on_front", "22", "page_for_posts", "21"));
        assertThat(parse("pagename=blog", stored).flags()).isEqualTo(PAGE);
        assertThat(parse("page_id=22", stored).flags()).isEqualTo(PAGE);
    }

    @Test
    void theFrontPageThatIsAlsoThePostsPage() {
        SiteOptions both = options(Map.of("show_on_front", "page", "page_on_front", "21", "page_for_posts", "21"));
        FlagParser.Parsed none = parse("", both);
        assertThat(none.flags()).isEqualTo(HOME.withPostsPage(true));
        assertThat(none.normalized().asMap()).isEqualTo(Map.of("page_id", "21"));
        FlagParser.Parsed paged = parse("paged=2", both);
        assertThat(paged.flags()).isEqualTo(HOME.withPostsPage(true).withPaged(true));
        assertThat(paged.normalized().asMap()).isEqualTo(Map.of("page_id", "21", "page", "2"));
        assertThat(parse("pagename=blog", both).flags()).isEqualTo(HOME.withPostsPage(true));
        assertThat(parse("feed=rss2", both).flags()).isEqualTo(NONE.withFeed(true));
    }

    @Test
    void thePrivacyPage() {
        FlagParser.Parsed byPath = parse("pagename=privacy-policy");
        assertThat(byPath.flags()).isEqualTo(PAGE.withPrivacyPolicy(true));
        assertThat(queried(byPath)).contains(24L);
        assertThat(byPath.flags().isPrivacyPolicy(BLOG_FRONT, byPath.queriedFromPath()))
                .isTrue();
        FlagParser.Parsed byId = parse("page_id=24");
        assertThat(byId.flags()).isEqualTo(PAGE.withPrivacyPolicy(true));
        assertThat(byId.flags().isPrivacyPolicy(BLOG_FRONT, Optional.empty())).isFalse();
        assertThat(flags("pagename=privacy-policy&embed=true"))
                .isEqualTo(PAGE.withPrivacyPolicy(true).withEmbed(true));
        assertThat(flags("pagename=about")
                        .isPrivacyPolicy(BLOG_FRONT, parse("pagename=about").queriedFromPath()))
                .isFalse();
        assertThat(staticFront("pagename=privacy-policy")).isEqualTo(PAGE.withPrivacyPolicy(true));
        assertThat(staticFront("page_id=24")).isEqualTo(PAGE.withPrivacyPolicy(true));
        // the privacy page is the front page: the stored flag is set from the corrected page_id, the method needs
        // the queried object (set by the fetch)
        SiteOptions frontIsPrivacy = options(Map.of(
                "show_on_front",
                "page",
                "page_on_front",
                "22",
                "page_for_posts",
                "21",
                "wp_page_for_privacy_policy",
                "22"));
        FlagParser.Parsed none = parse("", frontIsPrivacy);
        assertThat(none.flags()).isEqualTo(PAGE.withPrivacyPolicy(true));
        assertThat(none.flags().isPrivacyPolicy(frontIsPrivacy, Optional.empty()))
                .isFalse();
        QueriedObject home = new QueriedObject.Post(snapshot(22, "page", "publish", "home"));
        assertThat(none.flags().isPrivacyPolicy(frontIsPrivacy, Optional.of(home)))
                .isTrue();
        assertThat(parse("paged=2", frontIsPrivacy).flags())
                .isEqualTo(PAGE.withPrivacyPolicy(true).withPaged(true));
        assertThat(parse("name=home", frontIsPrivacy).flags()).isEqualTo(SINGLE);
        assertThat(parse("pagename=home", frontIsPrivacy).flags()).isEqualTo(PAGE.withPrivacyPolicy(true));
        // the privacy page is the posts page: the flag is set by id, whatever the other flags
        SiteOptions postsIsPrivacy = options(Map.of(
                "show_on_front",
                "page",
                "page_on_front",
                "22",
                "page_for_posts",
                "21",
                "wp_page_for_privacy_policy",
                "21"));
        assertThat(parse("pagename=blog", postsIsPrivacy).flags())
                .isEqualTo(HOME.withPostsPage(true).withPrivacyPolicy(true));
        assertThat(parse("page_id=21", postsIsPrivacy).flags())
                .isEqualTo(HOME.withPostsPage(true).withPrivacyPolicy(true));
    }

    // ---- FQ7 sanitising and 404 ----------------------------------------------------------------------------------

    @Test
    void sanitisingAndNotFound() {
        assertThat(flags("error=404")).isEqualTo(NONE.withIs404(true));
        assertThat(flags("error=404&p=10")).isEqualTo(NONE.withIs404(true));
        assertThat(parse("error=404&p=10").normalized().asMap()).isEqualTo(Map.of("error", "404", "p", "10"));
        assertThat(flags("error=403&p=10")).isEqualTo(SINGLE);
        assertThat(flags("error=404&robots=1")).isEqualTo(NONE.withIs404(true));
        assertThat(flags("p=-1")).isEqualTo(NONE.withIs404(true));
        assertThat(flags("paged=2&error=404")).isEqualTo(NONE.withIs404(true));
        assertThat(flags("feed=rss2&error=404")).isEqualTo(NONE.withIs404(true));
        assertThat(parse("post_type=Page").normalized().get("post_type")).contains("page");
        assertThat(parse("post_type=Pa%20ge!").normalized().get("post_type")).contains("page");
        assertThat(parse("post_type=p%C3%A4ge").normalized().get("post_type")).contains("pge");
        assertThat(parse("post_type=BOOK&year=2026").normalized().get("post_type"))
                .contains("book");
        assertThat(flags("post_type=BOOK&year=2026"))
                .isEqualTo(NONE.withYear(true).withDate(true).withArchive(true));
        assertThat(flags("post_type=page&page_id=20")).isEqualTo(PAGE);
        assertThat(FrontFlags.notFound()).isEqualTo(NONE.withIs404(true));
    }

    // ---- FQ10 the tax query --------------------------------------------------------------------------------------

    @Test
    void categoryIdsBecomeTermClausesWithChildren() {
        FlagParser.Parsed mixed = parse("cat=3,-4");
        assertThat(mixed.taxQuery())
                .isEqualTo(tax(
                        terms("category", TaxClause.Operator.IN, 3), terms("category", TaxClause.Operator.NOT_IN, 4)));
        assertThat(mixed.normalized().get("cat")).contains("-4,3");
        assertThat(parse("cat=4,3").taxQuery()).isEqualTo(tax(terms("category", TaxClause.Operator.IN, 3, 4)));
        assertThat(parse("cat=4,3").normalized().get("cat")).contains("3,4");
        assertThat(parse("cat=-3").taxQuery()).isEqualTo(tax(terms("category", TaxClause.Operator.NOT_IN, 3)));
        assertThat(parse("cat=0").taxQuery()).isEqualTo(TaxQuery.NONE);
        assertThat(parse("cat=0").normalized().get("cat")).contains("0");
        assertThat(parse("cat=abc").taxQuery()).isEqualTo(TaxQuery.NONE);
        assertThat(parse("cat=abc").normalized().get("cat")).contains("");
        assertThat(parse("cat=3,0,-0").taxQuery()).isEqualTo(tax(terms("category", TaxClause.Operator.IN, 3)));
        assertThat(parse("cat=3,0,-0").normalized().get("cat")).contains("0,0,3");
        assertThat(parse("cat=3%204").taxQuery()).isEqualTo(tax(terms("category", TaxClause.Operator.IN, 34)));
    }

    @Test
    void taxonomyQueryVariablesBecomeSlugClauses() {
        assertThat(parse("category_name=news/europe").taxQuery()).isEqualTo(tax(slugs("category", "europe")));
        assertThat(parse("category_name=news/europe").normalized().get("category_name"))
                .contains("europe");
        assertThat(parse("category_name=News").taxQuery()).isEqualTo(tax(slugs("category", "news")));
        assertThat(parse("category_name=News").normalized().get("category_name"))
                .contains("News");
        assertThat(parse("category_name=news,europe").taxQuery()).isEqualTo(tax(slugs("category", "europe", "news")));
        assertThat(parse("category_name=news%2Beurope").taxQuery())
                .isEqualTo(tax(slugs("category", "news"), slugs("category", "europe")));
        assertThat(parse("category_name=news%2Beurope").normalized().get("category_name"))
                .contains("news+europe");
        // the pieces are sorted and re-joined before the plus test: "a,b" is then one term
        assertThat(parse("category_name=a,b%2Bc").taxQuery())
                .isEqualTo(tax(slugs("category", "ab"), slugs("category", "c")));
        assertThat(parse("category_name=10,9,a%2Bb").taxQuery())
                .isEqualTo(tax(slugs("category", "910a"), slugs("category", "b")));
        assertThat(parse("category_name=10,9,a").taxQuery()).isEqualTo(tax(slugs("category", "9", "10", "a")));
        assertThat(parse("category_name=%2Bnews").taxQuery())
                .isEqualTo(tax(slugs("category", ""), slugs("category", "news")));
        assertThat(parse("category_name=news/europe%2Bsports/asia").taxQuery())
                .isEqualTo(tax(slugs("category", "asia")));
        assertThat(parse("category_name=X.Y/Z.W").taxQuery()).isEqualTo(tax(slugs("category", "z-w")));
        // the last segment is wp_basename's: trailing slashes are dropped first
        assertThat(parse("category_name=news/").normalized().get("category_name"))
                .contains("news");
        assertThat(parse("category_name=news/").taxQuery()).isEqualTo(tax(slugs("category", "news")));
        assertThat(parse("category_name=news//").normalized().get("category_name"))
                .contains("news");
        assertThat(parse("category_name=/news/").normalized().get("category_name"))
                .contains("news");
        assertThat(parse("category_name=news/europe/").normalized().get("category_name"))
                .contains("europe");
        assertThat(parse("category_name=/").normalized().get("category_name")).contains("");
        assertThat(parse("category_name=/").taxQuery()).isEqualTo(tax(slugs("category", "")));
        assertThat(parse("genre=scifi/").normalized().get("genre")).contains("scifi/");
        assertThat(parse("category_name=news,,europe").taxQuery())
                .isEqualTo(tax(slugs("category", "", "europe", "news")));
        assertThat(parse("category_name=%20news%20,%20europe").taxQuery())
                .isEqualTo(tax(slugs("category", "europe", "news")));
        assertThat(parse("genre=scifi").taxQuery()).isEqualTo(tax(slugs("genre", "scifi")));
        assertThat(parse("genre=scifi/fantasy").taxQuery()).isEqualTo(tax(slugs("genre", "scifi-fantasy")));
        assertThat(parse("genre=a,b%2Bc").taxQuery()).isEqualTo(tax(slugs("genre", "ab"), slugs("genre", "c")));
        assertThat(parse("genre=Sci-Fi").taxQuery()).isEqualTo(tax(slugs("genre", "sci-fi")));
        assertThat(parse("taxonomy=genre&term=X.Y").taxQuery()).isEqualTo(tax(slugs("genre", "x-y")));
        assertThat(parse("taxonomy=post_format&term=post-format-aside").taxQuery())
                .isEqualTo(tax(slugs("post_format", "post-format-aside")));
        assertThat(parse("taxonomy=nosuch&term=x").taxQuery()).isEqualTo(tax(slugs("nosuch", "x")));
        assertThat(parse("taxonomy=genre").taxQuery()).isEqualTo(TaxQuery.NONE);
        assertThat(parse("post_format=aside%2Bimage").taxQuery())
                .isEqualTo(tax(slugs("post_format", "aside"), slugs("post_format", "image")));
    }

    @Test
    void tagsSplitOnCommasOrPlusesAndSpaces() {
        assertThat(parse("tag=cars").taxQuery()).isEqualTo(tax(slugs("post_tag", "cars")));
        assertThat(parse("tag=cars%2Bpaint").taxQuery())
                .isEqualTo(tax(slugs("post_tag", "cars"), slugs("post_tag", "paint")));
        assertThat(parse("tag=cars%2Bpaint").normalized().get("tag")).contains("cars+paint");
        assertThat(parse("tag=cars,food").taxQuery()).isEqualTo(tax(slugs("post_tag", "cars", "food")));
        assertThat(parse("tag=cars%20paint").taxQuery())
                .isEqualTo(tax(slugs("post_tag", "cars"), slugs("post_tag", "paint")));
        assertThat(parse("tag=cars&cat=3").taxQuery())
                .isEqualTo(tax(terms("category", TaxClause.Operator.IN, 3), slugs("post_tag", "cars")));
        assertThat(parse("tag=cars,food&cat=3").taxQuery())
                .isEqualTo(tax(terms("category", TaxClause.Operator.IN, 3), slugs("post_tag", "cars", "food")));
        assertThat(parse("cat=3,-4&tag=cars").taxQuery())
                .isEqualTo(tax(
                        terms("category", TaxClause.Operator.IN, 3),
                        terms("category", TaxClause.Operator.NOT_IN, 4),
                        slugs("post_tag", "cars")));
        assertThat(parse("tag=Cars%20Paint!").taxQuery())
                .isEqualTo(tax(slugs("post_tag", "cars"), slugs("post_tag", "paint")));
        assertThat(parse("tag=Cars%20Paint!").normalized().get("tag")).contains("Cars Paint!");
        assertThat(parse("tag=cars%2B%2Bpaint").taxQuery())
                .isEqualTo(tax(slugs("post_tag", "cars"), slugs("post_tag", "paint")));
        assertThat(parse("tag=%2Bcars").taxQuery()).isEqualTo(tax(slugs("post_tag", ""), slugs("post_tag", "cars")));
        assertThat(parse("tag=,cars").taxQuery()).isEqualTo(tax(slugs("post_tag", "", "cars")));
        assertThat(parse("tag=a,b%2Bc").taxQuery()).isEqualTo(tax(slugs("post_tag", "a", "bc")));
        assertThat(parse("tag=A%2BB,c").taxQuery()).isEqualTo(tax(slugs("post_tag", "ab", "c")));
        assertThat(parse("tag=Cars!").taxQuery()).isEqualTo(tax(slugs("post_tag", "cars")));
        // a tag piece is a term slug field: the save-context sanitiser folds accents and the slug is written back
        assertThat(parse("tag=caf%C3%A9").taxQuery()).isEqualTo(tax(slugs("post_tag", "cafe")));
        assertThat(parse("tag=caf%C3%A9").normalized().get("tag")).contains("cafe");
        assertThat(parse("tag=Cars!").normalized().get("tag")).contains("cars");
        assertThat(parse("tag=x.y").normalized().get("tag")).contains("x-y");
        assertThat(parse("tag=a,Caf%C3%A9").taxQuery()).isEqualTo(tax(slugs("post_tag", "a", "cafe")));
        assertThat(parse("tag=a,Caf%C3%A9").normalized().get("tag")).contains("a,Café");
        assertThat(parse("tag=%C3%A9,%C3%A9").taxQuery()).isEqualTo(tax(slugs("post_tag", "e")));
        assertThat(parse("tag=caf%C3%A9&cat=3").taxQuery())
                .isEqualTo(tax(terms("category", TaxClause.Operator.IN, 3), slugs("post_tag", "cafe")));
        assertThat(parse("tag=caf%C3%A9&cat=3").normalized().get("tag")).contains("café");
        // the separators are the comma, the plus, CR, LF, tab and space: a form feed or vertical tab is part of the
        // slug
        assertThat(parse("tag=a%0Cb").taxQuery()).isEqualTo(tax(slugs("post_tag", "ab")));
        assertThat(parse("tag=a%0Cb").normalized().get("tag")).contains("ab");
        assertThat(parse("tag=a%0Bb").taxQuery()).isEqualTo(tax(slugs("post_tag", "ab")));
        assertThat(parse("tag=a%0Ab").taxQuery()).isEqualTo(tax(slugs("post_tag", "a"), slugs("post_tag", "b")));
        assertThat(parse("tag=a%09b").taxQuery()).isEqualTo(tax(slugs("post_tag", "a"), slugs("post_tag", "b")));
        assertThat(parse("tag=a%0Db").taxQuery()).isEqualTo(tax(slugs("post_tag", "a"), slugs("post_tag", "b")));
        assertThat(parse("tag=a%0Cb,c").taxQuery()).isEqualTo(tax(slugs("post_tag", "ab", "c")));
        assertThat(parse("tag=a%2Bb%0Cc").taxQuery()).isEqualTo(tax(slugs("post_tag", "a"), slugs("post_tag", "bc")));
        assertThat(parse("tag=x.y%2Bz").taxQuery()).isEqualTo(tax(slugs("post_tag", "x-y"), slugs("post_tag", "z")));
        assertThat(parse("tag=x.y").taxQuery()).isEqualTo(tax(slugs("post_tag", "x-y")));
        // the clause order follows WordPress: taxonomy and term, the taxonomies' own variables, cat, then tag
        assertThat(parse("tag=cars&cat=3&genre=scifi&taxonomy=post_format&term=post-format-aside&category_name=news")
                        .taxQuery())
                .isEqualTo(tax(
                        slugs("post_format", "post-format-aside"),
                        slugs("category", "news"),
                        slugs("genre", "scifi"),
                        terms("category", TaxClause.Operator.IN, 3),
                        slugs("post_tag", "cars")));
    }
}
