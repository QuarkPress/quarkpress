package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkword.core.PostTypeDefinition;
import org.quarkword.core.TaxonomyDefinition;
import org.quarkword.posts.runtime.BuiltInPostTypes;
import org.quarkword.taxonomy.runtime.BuiltInTaxonomies;

/**
 * WordPress 7.1.3's compiled rewrite table and its matching, recorded with PHP for the behaviour reference's
 * registrations ({@code book}, {@code genre}) and a few more shapes (a type without front, a hierarchical type, a type
 * without query variable or feeds, a hierarchical taxonomy, one without query variable, one with its own).
 */
class RewriteRulesTest {

    private static final String DEFAULT = "/%year%/%monthnum%/%day%/%postname%/";

    static final PostTypeDefinition BOOK = PostTypeDefinition.builder("book")
            .publicly(true)
            .hasArchive(true)
            .rewrite("books", true, true, true)
            .build();
    static final PostTypeDefinition NOVEL = PostTypeDefinition.builder("novel")
            .publicly(true)
            .hasArchive(true)
            .rewrite("novels", false, true, true)
            .build();
    static final PostTypeDefinition CHAPTER = PostTypeDefinition.builder("chapter")
            .publicly(true)
            .hierarchical(true)
            .rewrite("chapters", true, true, true)
            .build();
    static final PostTypeDefinition MEMO = PostTypeDefinition.builder("memo")
            .publicly(true)
            .noQueryVar()
            .hasArchive("archive-of-memos")
            .rewrite("memos", true, false, false)
            .build();
    static final TaxonomyDefinition GENRE =
            TaxonomyDefinition.builder("genre", List.of("book")).build();
    static final TaxonomyDefinition SHELF = TaxonomyDefinition.builder("shelf", List.of("book"))
            .hierarchical(true)
            .rewrite("shelves", true, true)
            .build();
    static final TaxonomyDefinition MOOD =
            TaxonomyDefinition.builder("mood", List.of("book")).noQueryVar().build();
    static final TaxonomyDefinition WITH_VAR = TaxonomyDefinition.builder("with_var_tax", List.of("book"))
            .queryVar("wvt")
            .rewrite("wv", false, false)
            .build();

    static List<PostTypeDefinition> types() {
        List<PostTypeDefinition> types = new ArrayList<>(BuiltInPostTypes.all());
        types.add(BOOK);
        return types;
    }

    static List<TaxonomyDefinition> taxonomies() {
        List<TaxonomyDefinition> taxonomies = new ArrayList<>(BuiltInTaxonomies.all());
        taxonomies.add(GENRE);
        return taxonomies;
    }

    static List<PostTypeDefinition> extraTypes() {
        List<PostTypeDefinition> types = types();
        types.add(NOVEL);
        types.add(CHAPTER);
        types.add(MEMO);
        return types;
    }

    static List<TaxonomyDefinition> extraTaxonomies() {
        List<TaxonomyDefinition> taxonomies = taxonomies();
        taxonomies.add(SHELF);
        taxonomies.add(MOOD);
        taxonomies.add(WITH_VAR);
        return taxonomies;
    }

    static RewriteRules rules(String structure) {
        return RewriteRules.of(Structure.of(structure, "", ""), types(), taxonomies(), false, false);
    }

    private static Map<String, String> vars(RewriteRules rules, String path) {
        Optional<RewriteRules.Match> match = rules.match(path);
        assertThat(match).as(path).isPresent();
        return match.get().vars();
    }

    /** The recorded table: one {@code regex<TAB>query} line per rule, the root group's {@code ?&} read as {@code ?}. */
    private static List<String> recorded(String name) {
        String resource = "rewrite-table-" + name + ".txt";
        try (InputStream in = RewriteRulesTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            List<String> lines = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.contains("hidden_tax")) {
                    lines.add(line.replace("index.php?&", "index.php?"));
                }
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> lines(RewriteRules rules) {
        List<String> lines = new ArrayList<>();
        for (RewriteRules.Rule rule : rules.rules()) {
            lines.add(rule.regex() + "\t" + rule.template());
        }
        return lines;
    }

    private static void assertTable(RewriteRules rules, String name) {
        assertThat(lines(rules)).containsExactlyElementsOf(recorded(name));
    }

    @Test
    void theDefaultTableIsWordPresss() {
        assertTable(rules(DEFAULT), "default");
    }

    @Test
    void theVerboseTableWithEveryRegistrationShapeIsWordPresss() {
        // A taxonomy registered as not public gets no rewrite from the registry, so WordPress's rules for one are
        // left out of the recording.
        RewriteRules rules =
                RewriteRules.of(Structure.of("/%postname%/", "", ""), extraTypes(), extraTaxonomies(), false, false);
        assertTable(rules, "postname-extras");
    }

    @Test
    void aFrontWithBasesOnASiteBelowTheRootIsWordPresss() {
        RewriteRules rules = RewriteRules.of(
                Structure.of("/blog/%postname%", "topics", "labels"), extraTypes(), extraTaxonomies(), true, false);
        assertTable(rules, "blog-bases-site-path");
    }

    @Test
    void theOtherStructuresAreWordPresss() {
        assertTable(rules("/index.php/%postname%/"), "pathinfo-postname");
        assertTable(rules("/%post_id%/%postname%/"), "post-id-postname");
        assertTable(rules("/%year%/%postname%/"), "year-postname");
        assertTable(rules("/%category%/%postname%/"), "category-postname");
        assertTable(rules("/%author%/%year%/%postname%/"), "author-year-postname");
        assertTable(rules("/%day%/%monthnum%/%year%/%postname%/"), "day-month-year-postname");
        assertTable(rules("/%year%/%monthnum%/%day%/%hour%/%minute%/%second%/%postname%/"), "time-postname");
    }

    @Test
    void plainModeHasNoRules() {
        assertThat(rules("").isEmpty()).isTrue();
        assertThat(rules("").match("anything")).isEmpty();
    }

    @Test
    void theExamplePathsOfTheDefaultStructure() {
        RewriteRules rules = rules(DEFAULT);
        assertThat(vars(rules, "2025/11/20/hello-world"))
                .isEqualTo(Map.of("year", "2025", "monthnum", "11", "day", "20", "name", "hello-world", "page", ""));
        assertThat(vars(rules, "2025/11/20/hello-world/2"))
                .isEqualTo(Map.of("year", "2025", "monthnum", "11", "day", "20", "name", "hello-world", "page", "2"));
        assertThat(vars(rules, "2025/11/20/hello-world/page/3"))
                .isEqualTo(Map.of("year", "2025", "monthnum", "11", "day", "20", "name", "hello-world", "paged", "3"));
        assertThat(vars(rules, "2025/11/23/second-post/photo")).isEqualTo(Map.of("attachment", "photo"));
        assertThat(vars(rules, "2025/11/23/second-post/attachment/7")).isEqualTo(Map.of("attachment", "7"));
        assertThat(vars(rules, "2025/11/23")).isEqualTo(Map.of("year", "2025", "monthnum", "11", "day", "23"));
        assertThat(vars(rules, "2025/11")).isEqualTo(Map.of("year", "2025", "monthnum", "11"));
        assertThat(vars(rules, "2025")).isEqualTo(Map.of("year", "2025"));
        assertThat(vars(rules, "2025/page/2")).isEqualTo(Map.of("year", "2025", "paged", "2"));
        assertThat(vars(rules, "category/news/releases")).isEqualTo(Map.of("category_name", "news/releases"));
        assertThat(vars(rules, "category/news/page/2")).isEqualTo(Map.of("category_name", "news", "paged", "2"));
        assertThat(vars(rules, "tag/java")).isEqualTo(Map.of("tag", "java"));
        assertThat(vars(rules, "genre/scifi")).isEqualTo(Map.of("genre", "scifi"));
        assertThat(vars(rules, "type/aside")).isEqualTo(Map.of("post_format", "aside"));
        assertThat(vars(rules, "author/alice/page/2")).isEqualTo(Map.of("author_name", "alice", "paged", "2"));
        assertThat(vars(rules, "search/java 21")).isEqualTo(Map.of("s", "java 21"));
        assertThat(vars(rules, "search/a/b")).isEqualTo(Map.of("s", "a/b"));
        assertThat(vars(rules, "page/2")).isEqualTo(Map.of("paged", "2"));
        assertThat(vars(rules, "feed")).isEqualTo(Map.of("feed", "feed"));
        assertThat(vars(rules, "comments/feed")).isEqualTo(Map.of("feed", "feed", "withcomments", "1"));
        assertThat(vars(rules, "books")).isEqualTo(Map.of("post_type", "book"));
        assertThat(vars(rules, "books/page/2")).isEqualTo(Map.of("post_type", "book", "paged", "2"));
        assertThat(vars(rules, "books/dune")).isEqualTo(Map.of("book", "dune", "page", ""));
        assertThat(vars(rules, "books/dune/2")).isEqualTo(Map.of("book", "dune", "page", "2"));
        assertThat(vars(rules, "about/team")).isEqualTo(Map.of("pagename", "about/team", "page", ""));
        assertThat(vars(rules, "about/team/2")).isEqualTo(Map.of("pagename", "about/team", "page", "2"));
        assertThat(vars(rules, "about/team/team-photo"))
                .isEqualTo(Map.of("pagename", "about/team/team-photo", "page", ""));
        assertThat(vars(rules, "about/team/attachment/team-photo")).isEqualTo(Map.of("attachment", "team-photo"));
        assertThat(vars(rules, "wp-json/wp/v2/posts")).isEqualTo(Map.of("rest_route", "/wp/v2/posts"));
        assertThat(vars(rules, "wp-json")).isEqualTo(Map.of("rest_route", "/"));
        assertThat(vars(rules, "robots.txt")).isEqualTo(Map.of("robots", "1"));
        assertThat(vars(rules, "favicon.ico")).isEqualTo(Map.of("favicon", "1"));
        assertThat(vars(rules, "sitemap.xmlanything")).isEqualTo(Map.of("sitemap", "index"));
        assertThat(vars(rules, "wp-sitemap.xml")).isEqualTo(Map.of("sitemap", "index"));
        assertThat(vars(rules, "wp-sitemap-posts-post-1.xml"))
                .isEqualTo(Map.of("sitemap", "posts", "sitemap-subtype", "post", "paged", "1"));
        assertThat(vars(rules, "feed/wp-rss2.php")).isEqualTo(Map.of("feed", "old"));
        assertThat(vars(rules, "wp-app.php/x")).isEqualTo(Map.of("error", "403"));
        assertThat(vars(rules, "wp-register.php")).isEqualTo(Map.of("register", "true"));
        assertThat(vars(rules, "2025/11/20/hello-world/trackback"))
                .isEqualTo(Map.of("year", "2025", "monthnum", "11", "day", "20", "name", "hello-world", "tb", "1"));
        assertThat(vars(rules, "2025/11/20/hello-world/embed"))
                .isEqualTo(
                        Map.of("year", "2025", "monthnum", "11", "day", "20", "name", "hello-world", "embed", "true"));
        assertThat(vars(rules, "2025/11/20/hello-world/rss2"))
                .isEqualTo(
                        Map.of("year", "2025", "monthnum", "11", "day", "20", "name", "hello-world", "feed", "rss2"));
        assertThat(vars(rules, "2025/11/20/hello-world/comment-page-2"))
                .isEqualTo(Map.of("year", "2025", "monthnum", "11", "day", "20", "name", "hello-world", "cpage", "2"));
        assertThat(vars(rules, "2025/1/5/x"))
                .isEqualTo(Map.of("year", "2025", "monthnum", "1", "day", "5", "name", "x", "page", ""));
        assertThat(vars(rules, "2025/11/20/hello-world/.")).isEqualTo(Map.of("attachment", "."));
        assertThat(vars(rules, "wp-signup.php")).isEqualTo(Map.of("pagename", "wp-signup.php", "page", ""));
        assertThat(rules.match("")).isEmpty();
    }

    @Test
    void aTrailingSlashIsTolerated() {
        RewriteRules rules = rules(DEFAULT);
        assertThat(vars(rules, "2025/11/20/hello-world/"))
                .isEqualTo(Map.of("year", "2025", "monthnum", "11", "day", "20", "name", "hello-world", "page", ""));
        assertThat(vars(rules, "books/")).isEqualTo(Map.of("post_type", "book"));
    }

    @Test
    void theRawPathIsMatchedBeforeTheDecodedOne() {
        RewriteRules rules = rules(DEFAULT);
        assertThat(vars(rules, "2025/11/20/caf%C3%A9").get("name")).isEqualTo("caf%C3%A9");
        assertThat(vars(rules, "2025/11/20/caf%c3%a9").get("name")).isEqualTo("caf%c3%a9");
        assertThat(vars(rules, "2025/11/20/café").get("name")).isEqualTo("café");
        assertThat(vars(rules, "tag/a+b")).isEqualTo(Map.of("tag", "a+b"));
        assertThat(vars(rules, "tag/a%2Bb")).isEqualTo(Map.of("tag", "a%2Bb"));
        assertThat(vars(rules, "search/java%2021")).isEqualTo(Map.of("s", "java%2021"));
        // only the decoded path fits the year's digits
        assertThat(vars(rules, "%32025/11")).isEqualTo(Map.of("year", "2025", "monthnum", "11"));
    }

    @Test
    void theFixedFileRulesNeedAHomeAtTheHostRoot() {
        RewriteRules below = RewriteRules.of(Structure.of(DEFAULT, "", ""), types(), taxonomies(), true, false);
        assertThat(below.match("robots.txt").map(match -> match.vars()))
                .contains(Map.of("pagename", "robots.txt", "page", ""));
        assertThat(below.match("favicon.ico").map(match -> match.vars()))
                .contains(Map.of("pagename", "favicon.ico", "page", ""));
        assertThat(below.match("sitemap.xml").map(match -> match.vars()))
                .contains(Map.of("pagename", "sitemap.xml", "page", ""));
        assertThat(below.match("feed/wp-rss2.php").map(match -> match.vars())).contains(Map.of("feed", "old"));
    }

    @Test
    void aStaticFrontPageAddsItsCommentPaging() {
        RewriteRules rules = RewriteRules.of(Structure.of(DEFAULT, "", ""), types(), taxonomies(), false, true);
        assertThat(vars(rules, "comment-page-3")).isEqualTo(Map.of("cpage", "3"));
        assertThat(rules(DEFAULT).match("comment-page-3").map(match -> match.vars()))
                .contains(Map.of("pagename", "comment-page-3", "page", ""));
    }

    @Test
    void verbosePageRulesComeFirstAndAreSkippedWhenRejected() {
        RewriteRules rules = rules("/%postname%/");
        assertThat(vars(rules, "hello-world/photo")).isEqualTo(Map.of("pagename", "hello-world/photo", "page", ""));
        Optional<RewriteRules.Match> attachment =
                rules.match("hello-world/photo", match -> !match.rule().pagenameCapture());
        assertThat(attachment.map(RewriteRules.Match::vars)).contains(Map.of("attachment", "photo"));
        assertThat(rules.match("hello-world", match -> !match.rule().pagenameCapture())
                        .map(RewriteRules.Match::vars))
                .contains(Map.of("name", "hello-world", "page", ""));
        assertThat(rules.match("about/team/x/y", match -> !match.rule().pagenameCapture()))
                .isEmpty();
    }

    @Test
    void thePagenameCaptureIsFlaggedOnPageRulesOnly() {
        RewriteRules rules = rules(DEFAULT);
        assertThat(rules.match("about/team").get().rule().pagenameCapture()).isTrue();
        assertThat(rules.match("2025/11/20/hello-world").get().rule().pagenameCapture())
                .isFalse();
        assertThat(rules.match("about/team/attachment/x").get().rule().pagenameCapture())
                .isFalse();
    }

    @Test
    void theExtraRegistrationsSetTheirVariables() {
        RewriteRules rules =
                RewriteRules.of(Structure.of("/%postname%/", "", ""), extraTypes(), extraTaxonomies(), false, false);
        assertThat(vars(rules, "chapters/a/b")).isEqualTo(Map.of("chapter", "a/b", "page", ""));
        assertThat(vars(rules, "chapters/a/b/2")).isEqualTo(Map.of("chapter", "a/b", "page", "2"));
        assertThat(vars(rules, "chapters/a/attachment/c")).isEqualTo(Map.of("attachment", "c"));
        assertThat(vars(rules, "memos/x")).isEqualTo(Map.of("post_type", "memo", "name", "x", "page", ""));
        assertThat(vars(rules, "memos/x/y")).isEqualTo(Map.of("attachment", "y"));
        assertThat(vars(rules, "archive-of-memos")).isEqualTo(Map.of("post_type", "memo"));
        assertThat(vars(rules, "novels/x")).isEqualTo(Map.of("novel", "x", "page", ""));
        assertThat(vars(rules, "mood/x")).isEqualTo(Map.of("taxonomy", "mood", "term", "x"));
        assertThat(vars(rules, "shelves/a/b")).isEqualTo(Map.of("shelf", "a/b"));
        assertThat(vars(rules, "wv/x/page/2")).isEqualTo(Map.of("wvt", "x", "paged", "2"));
    }

    @Test
    void phpUrldecode() {
        assertThat(RewriteRules.phpUrldecode("a+b%20c%C3%A9%zz%")).isEqualTo("a b cé%zz%");
        assertThat(RewriteRules.phpUrldecode("plain")).isEqualTo("plain");
    }
}
