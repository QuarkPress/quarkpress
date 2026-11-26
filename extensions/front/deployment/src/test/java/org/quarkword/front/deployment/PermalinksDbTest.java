package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.core.TermId;
import org.quarkword.core.UserId;
import org.quarkword.front.Permalinks;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.runtime.PostEntity;
import org.quarkword.posts.runtime.PostRepository;
import org.quarkword.security.UserProfile;
import org.quarkword.security.UserProfiles;
import org.quarkword.taxonomy.TermSnapshot;
import org.quarkword.taxonomy.Terms;

/**
 * The links WordPress 7.1.3 builds for the behaviour reference's cast (users alice and bob; posts, pages, attachments,
 * a book and a novel; categories, a tag, a genre and a post format), recorded with PHP for each permalink structure.
 */
class PermalinksDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            FrontApp.with(PermalinksDbTest.class, BookType.class, NovelType.class, GenreTaxonomy.class);

    private static final String HOME = "http://wp.test";
    private static final String DEFAULT = "/%year%/%monthnum%/%day%/%postname%/";

    static SiteId site;
    static UserId alice;
    static UserId bob;
    static TermId uncategorized;
    static TermId news;
    static TermId releases;
    static TermId java;
    static TermId scifi;
    static TermId aside;
    static PostId hello;
    static PostId second;
    static PostId noCategory;
    static PostId orphanAuthor;
    static PostId draft;
    static PostId autoDraft;
    static PostId secret;
    static PostId coming;
    static PostId trashed;
    static PostId pattern;
    static PostId samplePage;
    static PostId about;
    static PostId team;
    static PostId blog;
    static PostId draftPage;
    static PostId photo;
    static PostId logo;
    static PostId teamPhoto;
    static PostId seven;
    static PostId draftPhoto;
    static PostId secretPhoto;
    static PostId frontPhoto;
    static PostId dune;
    static PostId draftBook;
    static PostId novel;
    static PostId secretBook;
    static PostId draftStatusPhoto;
    static PostId exponentPhoto;

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Inject
    Permalinks permalinks;

    @Inject
    PostRepository posts;

    @Inject
    Terms terms;

    @Inject
    UserProfiles profiles;

    @BeforeEach
    void cast() {
        if (site == null) {
            site = TestData.site(dataSource);
            alice = TestData.profile(dataSource, "alice", "alice");
            bob = TestData.profile(dataSource, "bob", "bob-smith");
            // capabilities are read on the main site for writes and on the bound site for object checks
            TestData.member(dataSource, TestData.MAIN, alice, "[\"author\"]");
            TestData.member(dataSource, site, alice, "[\"author\"]");
            TestData.member(dataSource, TestData.MAIN, bob, "[\"subscriber\"]");
            TestData.member(dataSource, site, bob, "[\"subscriber\"]");
            uncategorized = TestData.term(dataSource, site, "category", "Uncategorized", "uncategorized", null);
            news = TestData.term(dataSource, site, "category", "News", "news", null);
            releases = TestData.term(dataSource, site, "category", "Releases", "releases", news);
            java = TestData.term(dataSource, site, "post_tag", "Java", "java", null);
            scifi = TestData.term(dataSource, site, "genre", "Scifi", "scifi", null);
            aside = TestData.term(dataSource, site, "post_format", "post-format-aside", "post-format-aside", null);
            hello = post("post", "publish", "hello-world", alice, null, "2025-11-20T10:15:30Z");
            TestData.assign(dataSource, site, hello, uncategorized);
            second = post("post", "publish", "second-post", alice, null, "2025-11-23T08:00:00Z");
            TestData.assign(dataSource, site, second, news);
            TestData.assign(dataSource, site, second, releases);
            TestData.assign(dataSource, site, second, java);
            noCategory = post("post", "publish", "no-category", alice, null, "2025-11-23T08:00:00Z");
            orphanAuthor = post("post", "publish", "orphan-author", null, null, "2025-11-23T08:00:00Z");
            draft = floating("post", "draft", "draft-post", alice, "2025-11-23T09:00:00Z");
            autoDraft = floating("post", "auto-draft", "", alice, "2025-11-23T09:30:00Z");
            secret = post("post", "private", "secret", alice, null, "2025-11-22T09:00:00Z");
            coming = post("post", "future", "coming", alice, null, "2026-01-05T09:00:00Z");
            trashed = post("post", "trash", "trashed", alice, null, "2025-11-23T08:00:00Z");
            pattern = post("wp_block", "publish", "pattern", alice, null, "2025-11-23T08:00:00Z");
            samplePage = post("page", "publish", "sample-page", alice, null, "2025-11-20T10:00:00Z");
            about = post("page", "publish", "about", alice, null, "2025-11-20T10:00:00Z");
            team = post("page", "publish", "team", alice, about, "2025-11-20T10:00:00Z");
            blog = post("page", "publish", "blog", alice, null, "2025-11-20T10:00:00Z");
            draftPage = floating("page", "draft", "draft-page", alice, "2025-11-20T10:00:00Z");
            photo = post("attachment", "inherit", "photo", alice, second, "2025-11-23T08:30:00Z");
            logo = post("attachment", "inherit", "logo", alice, null, "2025-11-23T08:30:00Z");
            teamPhoto = post("attachment", "inherit", "team-photo", alice, team, "2025-11-23T08:30:00Z");
            seven = post("attachment", "inherit", "7", alice, second, "2025-11-23T08:30:00Z");
            draftPhoto = post("attachment", "inherit", "draft-photo", alice, draft, "2025-11-23T08:30:00Z");
            secretPhoto = post("attachment", "inherit", "private-photo", alice, secret, "2025-11-23T08:30:00Z");
            frontPhoto = post("attachment", "inherit", "front-photo", alice, samplePage, "2025-11-23T08:30:00Z");
            dune = post("book", "publish", "dune", alice, null, "2025-11-23T08:00:00Z");
            draftBook = floating("book", "draft", "draft-book", alice, "2025-11-23T08:00:00Z");
            novel = post("novel", "publish", "dune", alice, null, "2025-11-23T08:00:00Z");
            secretBook = post("book", "private", "secret-book", alice, null, "2025-11-23T08:00:00Z");
            draftStatusPhoto = post("attachment", "draft", "draft-status-photo", alice, second, "2025-11-23T08:30:00Z");
            exponentPhoto = post("attachment", "inherit", "1e5", alice, second, "2025-11-23T08:30:00Z");
        }
        TestData.textOption(dataSource, site, "home", HOME);
        TestData.textOption(dataSource, site, "siteurl", HOME);
        TestData.textOption(dataSource, site, "permalink_structure", DEFAULT);
        TestData.textOption(dataSource, site, "category_base", "");
        TestData.textOption(dataSource, site, "tag_base", "");
        TestData.textOption(dataSource, site, "show_on_front", "posts");
        TestData.siteOption(dataSource, site, "page_on_front", "0");
        TestData.siteOption(dataSource, site, "page_for_posts", "0");
        TestData.siteOption(dataSource, site, "default_category", Long.toString(uncategorized.value()));
    }

    private PostId post(String type, String status, String slug, UserId author, PostId parent, String date) {
        return TestData.post(dataSource, site, type, status, slug, author, parent, date, false);
    }

    private PostId floating(String type, String status, String slug, UserId author, String date) {
        return TestData.post(dataSource, site, type, status, slug, author, null, date, true);
    }

    private void structure(String structure) {
        TestData.textOption(dataSource, site, "permalink_structure", structure);
    }

    private void frontPage(PostId front, PostId postsPage) {
        TestData.textOption(dataSource, site, "show_on_front", "page");
        TestData.siteOption(dataSource, site, "page_on_front", Long.toString(front.value()));
        TestData.siteOption(dataSource, site, "page_for_posts", Long.toString(postsPage.value()));
    }

    private void anonymous(Callable<Void> work) throws Exception {
        Identities.onSite(site, Identities.anonymous(), work);
    }

    private void as(UserId user, Callable<Void> work) throws Exception {
        Identities.onSite(site, Identities.user(user), work);
    }

    private PostSnapshot snapshot(PostId post) {
        return posts.findPost(site, post).map(PostEntity::toSnapshot).orElseThrow();
    }

    private String post(PostId post) {
        return permalinks.post(snapshot(post));
    }

    private String sample(PostId post) {
        return permalinks.postSample(snapshot(post));
    }

    private String term(String taxonomy, TermId term) {
        TermSnapshot found = terms.find(term, taxonomy).orElseThrow();
        return permalinks.term(taxonomy, found);
    }

    private String author(UserId user) {
        UserProfile profile = profiles.find(user).orElseThrow();
        return permalinks.author(profile);
    }

    private static String id(PostId post) {
        return Long.toString(post.value());
    }

    @Test
    void postsUnderTheDefaultStructure() throws Exception {
        anonymous(() -> {
            assertThat(permalinks.plain()).isFalse();
            assertThat(permalinks.trailingSlashes()).isTrue();
            assertThat(post(hello)).isEqualTo(HOME + "/2025/11/20/hello-world/");
            assertThat(post(second)).isEqualTo(HOME + "/2025/11/23/second-post/");
            assertThat(post(draft)).isEqualTo(HOME + "/?p=" + id(draft));
            assertThat(post(coming)).isEqualTo(HOME + "/?p=" + id(coming));
            assertThat(post(trashed)).isEqualTo(HOME + "/?p=" + id(trashed));
            assertThat(post(secret)).isEqualTo(HOME + "/?p=" + id(secret));
            assertThat(post(pattern)).isEqualTo(HOME + "/2025/11/23/pattern/");
            assertThat(sample(draft)).isEqualTo(HOME + "/2025/11/23/%postname%/");
            // get_sample_permalink reads an auto-draft as published, as it reads a draft
            assertThat(post(autoDraft)).isEqualTo(HOME + "/?p=" + id(autoDraft));
            assertThat(sample(autoDraft)).isEqualTo(HOME + "/2025/11/23/%postname%/");
            assertThat(sample(hello)).isEqualTo(HOME + "/2025/11/20/%postname%/");
            assertThat(sample(coming)).isEqualTo(HOME + "/2026/01/05/%postname%/");
            assertThat(sample(secret)).isEqualTo(HOME + "/?p=" + id(secret));
            return null;
        });
        as(alice, () -> {
            assertThat(post(secret)).isEqualTo(HOME + "/2025/11/22/secret/");
            assertThat(sample(secret)).isEqualTo(HOME + "/2025/11/22/%postname%/");
            return null;
        });
        as(bob, () -> {
            assertThat(post(secret)).isEqualTo(HOME + "/?p=" + id(secret));
            return null;
        });
    }

    @Test
    void pagesAndAttachmentsUnderTheDefaultStructure() throws Exception {
        anonymous(() -> {
            assertThat(post(team)).isEqualTo(HOME + "/about/team/");
            assertThat(post(about)).isEqualTo(HOME + "/about/");
            assertThat(post(draftPage)).isEqualTo(HOME + "/?page_id=" + id(draftPage));
            assertThat(sample(team)).isEqualTo(HOME + "/about/%pagename%/");
            assertThat(sample(draftPage)).isEqualTo(HOME + "/%pagename%/");
            assertThat(post(photo)).isEqualTo(HOME + "/2025/11/23/second-post/photo/");
            assertThat(post(seven)).isEqualTo(HOME + "/2025/11/23/second-post/attachment/7/");
            assertThat(post(teamPhoto)).isEqualTo(HOME + "/about/team/team-photo/");
            assertThat(post(logo)).isEqualTo(HOME + "/logo/");
            assertThat(post(draftPhoto)).isEqualTo(HOME + "/?attachment_id=" + id(draftPhoto));
            assertThat(post(secretPhoto)).isEqualTo(HOME + "/?attachment_id=" + id(secretPhoto));
            assertThat(sample(photo)).isEqualTo(HOME + "/2025/11/23/second-post/%postname%/");
            assertThat(sample(seven)).isEqualTo(HOME + "/2025/11/23/second-post/%postname%/");
            assertThat(sample(draftPhoto)).isEqualTo(HOME + "/?attachment_id=" + id(draftPhoto));
            assertThat(sample(logo)).isEqualTo(HOME + "/?attachment_id=" + id(logo));
            assertThat(post(draftStatusPhoto)).isEqualTo(HOME + "/2025/11/23/second-post/draft-status-photo/");
            assertThat(post(exponentPhoto)).isEqualTo(HOME + "/2025/11/23/second-post/attachment/1e5/");
            return null;
        });
        as(alice, () -> {
            assertThat(post(secretPhoto)).isEqualTo(HOME + "/2025/11/22/secret/private-photo/");
            return null;
        });
    }

    @Test
    void theFrontPageAndThePostsPage() throws Exception {
        frontPage(samplePage, blog);
        anonymous(() -> {
            assertThat(post(samplePage)).isEqualTo(HOME + "/");
            assertThat(sample(samplePage)).isEqualTo(HOME + "/");
            assertThat(permalinks.postTypeArchive("post")).contains(HOME + "/blog/");
            assertThat(post(blog)).isEqualTo(HOME + "/blog/");
            assertThat(post(frontPhoto)).isEqualTo(HOME + "/sample-page/front-photo/");
            assertThat(permalinks.paged("/", 2)).isEqualTo(HOME + "/page/2/");
            return null;
        });
        TestData.textOption(dataSource, site, "show_on_front", "posts");
        anonymous(() -> {
            assertThat(post(samplePage)).isEqualTo(HOME + "/sample-page/");
            assertThat(permalinks.postTypeArchive("post")).contains(HOME);
            return null;
        });
        frontPage(draftPage, blog);
        anonymous(() -> {
            assertThat(post(draftPage)).isEqualTo(HOME + "/");
            return null;
        });
    }

    @Test
    void termsAuthorsDatesSearchesAndArchivesUnderTheDefaultStructure() throws Exception {
        anonymous(() -> {
            assertThat(term("category", releases)).isEqualTo(HOME + "/category/news/releases/");
            assertThat(term("category", news)).isEqualTo(HOME + "/category/news/");
            assertThat(term("post_tag", java)).isEqualTo(HOME + "/tag/java/");
            assertThat(term("genre", scifi)).isEqualTo(HOME + "/genre/scifi/");
            assertThat(term("post_format", aside)).isEqualTo(HOME + "/type/aside/");
            assertThat(author(alice)).isEqualTo(HOME + "/author/alice/");
            assertThat(author(bob)).isEqualTo(HOME + "/author/bob-smith/");
            assertThat(permalinks.year(2025)).isEqualTo(HOME + "/2025/");
            assertThat(permalinks.month(2025, 3)).isEqualTo(HOME + "/2025/03/");
            assertThat(permalinks.day(2025, 3, 7)).isEqualTo(HOME + "/2025/03/07/");
            assertThat(permalinks.search("java 21")).isEqualTo(HOME + "/search/java+21/");
            assertThat(permalinks.search("a/b")).isEqualTo(HOME + "/search/a/b/");
            assertThat(permalinks.search("a+b")).isEqualTo(HOME + "/search/a%2Bb/");
            assertThat(permalinks.search("ümlaut&x")).isEqualTo(HOME + "/search/%C3%BCmlaut%26x/");
            assertThat(permalinks.postTypeArchive("book")).contains(HOME + "/books/");
            assertThat(permalinks.postTypeArchive("novel")).contains(HOME + "/novels/");
            assertThat(permalinks.postTypeArchive("post")).contains(HOME);
            assertThat(permalinks.postTypeArchive("page")).isEmpty();
            assertThat(permalinks.postTypeArchive("attachment")).isEmpty();
            assertThat(permalinks.postTypeArchive("unknown")).isEmpty();
            assertThat(permalinks.postFormat("aside")).isEqualTo(HOME + "/type/aside/");
            assertThat(post(dune)).isEqualTo(HOME + "/books/dune/");
            assertThat(post(novel)).isEqualTo(HOME + "/novels/dune/");
            assertThat(post(draftBook)).isEqualTo(HOME + "/?post_type=book&p=" + id(draftBook));
            assertThat(sample(dune)).isEqualTo(HOME + "/books/%pagename%/");
            assertThat(sample(draftBook)).isEqualTo(HOME + "/books/%pagename%/");
            assertThat(post(secretBook)).isEqualTo(HOME + "/?post_type=book&p=" + id(secretBook));
            assertThat(sample(secretBook)).isEqualTo(HOME + "/books/%pagename%/");
            return null;
        });
        as(alice, () -> {
            assertThat(post(secretBook)).isEqualTo(HOME + "/books/secret-book/");
            assertThat(sample(secretBook)).isEqualTo(HOME + "/books/%pagename%/");
            return null;
        });
    }

    @Test
    void pagingUnderTheDefaultStructure() throws Exception {
        anonymous(() -> {
            assertThat(permalinks.paged("/category/news/", 2)).isEqualTo(HOME + "/category/news/page/2/");
            assertThat(permalinks.paged("/category/news/page/2/?foo=1", 3))
                    .isEqualTo(HOME + "/category/news/page/3/?foo=1");
            assertThat(permalinks.paged("/category/news/page/2/?foo=1", 1)).isEqualTo(HOME + "/category/news/?foo=1");
            assertThat(permalinks.paged("/", 2)).isEqualTo(HOME + "/page/2/");
            assertThat(permalinks.paged("/", 1)).isEqualTo(HOME + "/");
            assertThat(permalinks.paged("/?s=java", 2)).isEqualTo(HOME + "/page/2/?s=java");
            assertThat(permalinks.paged("/?s=java+21&x=a%2Fb", 2)).isEqualTo(HOME + "/page/2/?s=java+21&x=a%2Fb");
            assertThat(permalinks.paged("/category/news/?paged=5", 2)).isEqualTo(HOME + "/category/news/page/2/");
            assertThat(permalinks.paged("/index.php/category/news/", 2)).isEqualTo(HOME + "/category/news/page/2/");
            assertThat(permalinks.paged("/Index.php/x/", 2)).isEqualTo(HOME + "/x/page/2/");
            assertThat(permalinks.paged("//x//", 2)).isEqualTo(HOME + "/x/page/2/");
            assertThat(permalinks.paged("/x/?paged=2&y=1#frag", 3)).isEqualTo(HOME + "/x/page/3/?y=1#frag");
            assertThat(permalinks.paged("/x/?paged=2", 3)).isEqualTo(HOME + "/x/page/3/");
            assertThat(permalinks.paged("/x/?a=1&paged=2", 3)).isEqualTo(HOME + "/x/page/3/?a=1");
            assertThat(permalinks.paged("/x/?a=1&paged=2&b", 3)).isEqualTo(HOME + "/x/page/3/?a=1&b");
            assertThat(permalinks.paged("/mypage/2/", 3)).isEqualTo(HOME + "/my/page/3/");
            assertThat(permalinks.paged("/x/PAGE/2/", 3)).isEqualTo(HOME + "/x/PAGE/2/page/3/");
            assertThat(permalinks.paged("/0/", 2)).isEqualTo(HOME + "/page/2/");
            assertThat(permalinks.paged("/0/", 1)).isEqualTo(HOME + "/");
            assertThat(permalinks.paged("/x/0/", 2)).isEqualTo(HOME + "/x/0/page/2/");
            return null;
        });
    }

    @Test
    void theCategoryStructure() throws Exception {
        structure("/%category%/%postname%/");
        anonymous(() -> {
            assertThat(post(hello)).isEqualTo(HOME + "/uncategorized/hello-world/");
            assertThat(post(second)).isEqualTo(HOME + "/news/second-post/");
            assertThat(post(noCategory)).isEqualTo(HOME + "/uncategorized/no-category/");
            assertThat(post(photo)).isEqualTo(HOME + "/news/second-post/attachment/photo/");
            assertThat(post(teamPhoto)).isEqualTo(HOME + "/about/team/attachment/team-photo/");
            assertThat(post(logo)).isEqualTo(HOME + "/logo/");
            assertThat(sample(second)).isEqualTo(HOME + "/news/%postname%/");
            assertThat(post(team)).isEqualTo(HOME + "/about/team/");
            return null;
        });
        TestData.siteOption(dataSource, site, "default_category", Long.toString(releases.value()));
        anonymous(() -> {
            assertThat(post(noCategory)).isEqualTo(HOME + "/releases/no-category/");
            assertThat(post(second)).isEqualTo(HOME + "/news/second-post/");
            return null;
        });
        TestData.siteOption(dataSource, site, "default_category", "999999");
        anonymous(() -> {
            assertThat(post(noCategory)).isEqualTo(HOME + "/no-category/");
            return null;
        });
    }

    @Test
    void theAuthorStructure() throws Exception {
        structure("/%author%/%year%/%postname%/");
        anonymous(() -> {
            assertThat(post(second)).isEqualTo(HOME + "/alice/2025/second-post/");
            assertThat(post(orphanAuthor)).isEqualTo(HOME + "/2025/orphan-author/");
            return null;
        });
    }

    @Test
    void aStructureWithAFront() throws Exception {
        structure("/blog/%postname%/");
        anonymous(() -> {
            assertThat(post(hello)).isEqualTo(HOME + "/blog/hello-world/");
            assertThat(post(about)).isEqualTo(HOME + "/about/");
            assertThat(post(team)).isEqualTo(HOME + "/about/team/");
            assertThat(term("category", releases)).isEqualTo(HOME + "/blog/category/news/releases/");
            assertThat(term("post_tag", java)).isEqualTo(HOME + "/blog/tag/java/");
            assertThat(term("genre", scifi)).isEqualTo(HOME + "/blog/genre/scifi/");
            assertThat(author(alice)).isEqualTo(HOME + "/blog/author/alice/");
            assertThat(permalinks.search("java 21")).isEqualTo(HOME + "/search/java+21/");
            assertThat(permalinks.month(2025, 3)).isEqualTo(HOME + "/blog/2025/03/");
            assertThat(post(dune)).isEqualTo(HOME + "/blog/books/dune/");
            assertThat(permalinks.postTypeArchive("book")).contains(HOME + "/blog/books/");
            assertThat(post(novel)).isEqualTo(HOME + "/novels/dune/");
            assertThat(permalinks.postTypeArchive("novel")).contains(HOME + "/novels/");
            assertThat(post(logo)).isEqualTo(HOME + "/logo/");
            assertThat(post(photo)).isEqualTo(HOME + "/blog/second-post/photo/");
            assertThat(permalinks.postFormat("aside")).isEqualTo(HOME + "/blog/type/aside/");
            assertThat(permalinks.paged("/blog/", 2)).isEqualTo(HOME + "/blog/page/2/");
            return null;
        });
        TestData.textOption(dataSource, site, "category_base", "topics");
        TestData.textOption(dataSource, site, "tag_base", "labels");
        anonymous(() -> {
            assertThat(term("category", releases)).isEqualTo(HOME + "/topics/news/releases/");
            assertThat(term("post_tag", java)).isEqualTo(HOME + "/labels/java/");
            return null;
        });
    }

    @Test
    void aStructureWithoutTrailingSlash() throws Exception {
        structure("/%postname%");
        anonymous(() -> {
            assertThat(permalinks.trailingSlashes()).isFalse();
            assertThat(post(hello)).isEqualTo(HOME + "/hello-world");
            assertThat(post(team)).isEqualTo(HOME + "/about/team");
            assertThat(term("category", releases)).isEqualTo(HOME + "/category/news/releases");
            assertThat(post(photo)).isEqualTo(HOME + "/second-post/photo");
            assertThat(post(logo)).isEqualTo(HOME + "/logo");
            assertThat(permalinks.month(2025, 3)).isEqualTo(HOME + "/2025/03");
            assertThat(permalinks.search("java 21")).isEqualTo(HOME + "/search/java+21");
            assertThat(permalinks.postTypeArchive("book")).contains(HOME + "/books");
            assertThat(author(alice)).isEqualTo(HOME + "/author/alice");
            assertThat(permalinks.paged("/category/news", 2)).isEqualTo(HOME + "/category/news/page/2");
            assertThat(permalinks.paged("/category/news/page/2", 3)).isEqualTo(HOME + "/category/news/page/3");
            assertThat(permalinks.paged("/", 1)).isEqualTo(HOME);
            assertThat(permalinks.paged("/", 2)).isEqualTo(HOME + "/page/2");
            return null;
        });
    }

    @Test
    void pathinfoStructures() throws Exception {
        structure("/index.php/%year%/%monthnum%/%day%/%postname%/");
        anonymous(() -> {
            assertThat(post(hello)).isEqualTo(HOME + "/index.php/2025/11/20/hello-world/");
            assertThat(post(team)).isEqualTo(HOME + "/index.php/about/team/");
            assertThat(term("category", releases)).isEqualTo(HOME + "/index.php/category/news/releases/");
            assertThat(term("post_tag", java)).isEqualTo(HOME + "/index.php/tag/java/");
            assertThat(term("genre", scifi)).isEqualTo(HOME + "/index.php/genre/scifi/");
            assertThat(author(alice)).isEqualTo(HOME + "/index.php/author/alice/");
            assertThat(permalinks.month(2025, 3)).isEqualTo(HOME + "/index.php/2025/03/");
            assertThat(permalinks.search("java 21")).isEqualTo(HOME + "/index.php/search/java+21/");
            assertThat(permalinks.postTypeArchive("book")).contains(HOME + "/index.php/books/");
            assertThat(post(dune)).isEqualTo(HOME + "/index.php/books/dune/");
            assertThat(post(photo)).isEqualTo(HOME + "/index.php/2025/11/23/second-post/photo/");
            assertThat(post(logo)).isEqualTo(HOME + "/logo/");
            assertThat(permalinks.postFormat("aside")).isEqualTo(HOME + "/index.php/type/aside/");
            assertThat(permalinks.paged("/category/news/", 2)).isEqualTo(HOME + "/index.php/category/news/page/2/");
            assertThat(permalinks.paged("/index.php/category/news/", 2))
                    .isEqualTo(HOME + "/index.php/category/news/page/2/");
            assertThat(permalinks.paged("/", 2)).isEqualTo(HOME + "/index.php/page/2/");
            assertThat(permalinks.paged("/index.php", 2)).isEqualTo(HOME + "/index.php/page/2/");
            assertThat(permalinks.paged("/", 1)).isEqualTo(HOME + "/");
            assertThat(permalinks.paged("/0/", 1)).isEqualTo(HOME + "/index.php/");
            assertThat(permalinks.paged("/0/", 2)).isEqualTo(HOME + "/index.php/page/2/");
            return null;
        });
        structure("/index.php/%postname%/");
        TestData.textOption(dataSource, site, "category_base", "topics");
        anonymous(() -> {
            assertThat(term("category", releases)).isEqualTo(HOME + "/index.php/topics/news/releases/");
            return null;
        });
    }

    @Test
    void aPostIdStructureMovesDateArchives() throws Exception {
        structure("/%post_id%/%postname%/");
        anonymous(() -> {
            assertThat(post(hello)).isEqualTo(HOME + "/" + id(hello) + "/hello-world/");
            assertThat(permalinks.year(2025)).isEqualTo(HOME + "/date/2025/");
            assertThat(permalinks.month(2025, 3)).isEqualTo(HOME + "/date/2025/03/");
            assertThat(permalinks.day(2025, 3, 7)).isEqualTo(HOME + "/date/2025/03/07/");
            return null;
        });
    }

    @Test
    void aReversedDateStructure() throws Exception {
        structure("/%day%/%monthnum%/%year%/%postname%/");
        anonymous(() -> {
            assertThat(post(hello)).isEqualTo(HOME + "/20/11/2025/hello-world/");
            assertThat(permalinks.day(2025, 3, 7)).isEqualTo(HOME + "/07/03/2025/");
            assertThat(permalinks.month(2025, 3)).isEqualTo(HOME + "/03/2025/");
            assertThat(permalinks.year(2025)).isEqualTo(HOME + "/2025/");
            return null;
        });
    }

    @Test
    void plainMode() throws Exception {
        structure("");
        anonymous(() -> {
            assertThat(permalinks.plain()).isTrue();
            assertThat(permalinks.trailingSlashes()).isFalse();
            assertThat(post(hello)).isEqualTo(HOME + "/?p=" + id(hello));
            assertThat(post(team)).isEqualTo(HOME + "/?page_id=" + id(team));
            assertThat(post(photo)).isEqualTo(HOME + "/?attachment_id=" + id(photo));
            assertThat(post(logo)).isEqualTo(HOME + "/?attachment_id=" + id(logo));
            assertThat(post(dune)).isEqualTo(HOME + "/?book=dune");
            assertThat(post(draftBook)).isEqualTo(HOME + "/?post_type=book&p=" + id(draftBook));
            assertThat(post(draft)).isEqualTo(HOME + "/?p=" + id(draft));
            assertThat(post(pattern)).isEqualTo(HOME + "/?p=" + id(pattern));
            assertThat(sample(draft)).isEqualTo(HOME + "/?p=" + id(draft));
            assertThat(sample(team)).isEqualTo(HOME + "/?page_id=" + id(team));
            assertThat(sample(draftBook)).isEqualTo(HOME + "/?post_type=book&p=" + id(draftBook));
            assertThat(term("category", releases)).isEqualTo(HOME + "/?cat=" + releases.value());
            assertThat(term("post_tag", java)).isEqualTo(HOME + "/?tag=java");
            assertThat(term("genre", scifi)).isEqualTo(HOME + "/?genre=scifi");
            assertThat(term("post_format", aside)).isEqualTo(HOME + "/?post_format=aside");
            assertThat(author(alice)).isEqualTo(HOME + "/?author=" + alice.value());
            assertThat(permalinks.month(2025, 11)).isEqualTo(HOME + "/?m=202511");
            assertThat(permalinks.year(2025)).isEqualTo(HOME + "/?m=2025");
            assertThat(permalinks.day(2025, 3, 7)).isEqualTo(HOME + "/?m=20250307");
            assertThat(permalinks.search("java 21")).isEqualTo(HOME + "/?s=java+21");
            assertThat(permalinks.postTypeArchive("book")).contains(HOME + "/?post_type=book");
            assertThat(permalinks.postTypeArchive("post")).contains(HOME);
            assertThat(permalinks.postTypeArchive("page")).isEmpty();
            assertThat(permalinks.postFormat("aside")).isEqualTo(HOME + "/?post_format=aside");
            assertThat(permalinks.paged("/?cat=4", 2)).isEqualTo(HOME + "/?cat=4&paged=2");
            assertThat(permalinks.paged("/?cat=4&paged=3", 2)).isEqualTo(HOME + "/?cat=4&paged=2");
            assertThat(permalinks.paged("/?s=java+21&x=a%2Fb", 2)).isEqualTo(HOME + "/?s=java+21&x=a%2Fb&paged=2");
            assertThat(permalinks.paged("/", 2)).isEqualTo(HOME + "/?paged=2");
            assertThat(permalinks.paged("/?cat=4", 1)).isEqualTo(HOME + "/?cat=4");
            assertThat(permalinks.paged("/x/?paged=3", 2)).isEqualTo(HOME + "/x/?paged=2");
            assertThat(permalinks.paged("/x/", 1)).isEqualTo(HOME + "/x/");
            assertThat(permalinks.paged("/?paged=3", 1)).isEqualTo(HOME + "/");
            assertThat(permalinks.paged("/?paged=3&a=1", 1)).isEqualTo(HOME + "/?a=1");
            assertThat(permalinks.paged("/0/", 2)).isEqualTo(HOME + "/0/?paged=2");
            return null;
        });
        frontPage(samplePage, blog);
        anonymous(() -> {
            assertThat(post(samplePage)).isEqualTo(HOME + "/");
            assertThat(permalinks.postTypeArchive("post")).contains(HOME + "/?page_id=" + id(blog));
            return null;
        });
    }

    @Test
    void aHomeWithAPath() throws Exception {
        TestData.textOption(dataSource, site, "home", HOME + "/site2");
        anonymous(() -> {
            assertThat(post(hello)).isEqualTo(HOME + "/site2/2025/11/20/hello-world/");
            assertThat(term("post_tag", java)).isEqualTo(HOME + "/site2/tag/java/");
            assertThat(permalinks.paged("/site2/category/news/", 2)).isEqualTo(HOME + "/site2/category/news/page/2/");
            assertThat(permalinks.paged("/site2/", 2)).isEqualTo(HOME + "/site2/page/2/");
            assertThat(permalinks.paged("/SITE2/x/", 2)).isEqualTo(HOME + "/site2/x/page/2/");
            assertThat(permalinks.paged("/site2/category/news/page/2/?x=1", 3))
                    .isEqualTo(HOME + "/site2/category/news/page/3/?x=1");
            return null;
        });
        structure("");
        anonymous(() -> {
            assertThat(permalinks.paged("/site2/?cat=4", 2)).isEqualTo(HOME + "/site2/?cat=4&paged=2");
            assertThat(post(hello)).isEqualTo(HOME + "/site2/?p=" + id(hello));
            return null;
        });
    }

    @Test
    void anUnknownTaxonomyOrTypeFallsBackToThePlainForms() throws Exception {
        anonymous(() -> {
            Optional<String> none = permalinks.postTypeArchive("nosuchtype");
            assertThat(none).isEmpty();
            return null;
        });
    }
}
