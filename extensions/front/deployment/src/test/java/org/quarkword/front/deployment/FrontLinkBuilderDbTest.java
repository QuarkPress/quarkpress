package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.core.TermId;
import org.quarkword.core.UserId;
import org.quarkword.front.runtime.FrontLinkBuilder;
import org.quarkword.posts.LinkBuilder;
import org.quarkword.posts.PostChanges;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.Posts;
import org.quarkword.posts.runtime.PostEntity;
import org.quarkword.posts.runtime.PostRepository;

/**
 * With the front module deployed, the posts module's {@code LinkBuilder} is the front one: links follow the permalink
 * structure, and a new post's guid is its pretty permalink when it is published.
 */
class FrontLinkBuilderDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            FrontApp.with(FrontLinkBuilderDbTest.class, BookType.class, GenreTaxonomy.class);

    private static final String HOME = "http://wp.test";

    static SiteId site;
    static UserId alice;
    static TermId news;
    static TermId scifi;
    static PostId hello;

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Inject
    LinkBuilder links;

    @Inject
    Posts posts;

    @Inject
    PostRepository repository;

    @BeforeEach
    void cast() {
        if (site == null) {
            site = TestData.site(dataSource);
            alice = TestData.profile(dataSource, "alice", "alice-" + site.value());
            // capabilities are read on the main site for writes and on the bound site for object checks
            TestData.member(dataSource, TestData.MAIN, alice, "[\"editor\"]");
            TestData.member(dataSource, site, alice, "[\"editor\"]");
            news = TestData.term(dataSource, site, "category", "News", "news", null);
            scifi = TestData.term(dataSource, site, "genre", "Scifi", "scifi", null);
            hello = TestData.post(
                    dataSource, site, "post", "publish", "hello-world", alice, null, "2025-11-20T10:15:30Z", false);
        }
        TestData.textOption(dataSource, site, "home", HOME);
        TestData.textOption(dataSource, site, "siteurl", HOME);
        TestData.textOption(dataSource, site, "permalink_structure", "/%year%/%monthnum%/%day%/%postname%/");
    }

    private PostSnapshot snapshot(PostId post) {
        return repository.findPost(site, post).map(PostEntity::toSnapshot).orElseThrow();
    }

    @Test
    void theFrontBuilderReplacesThePlainOne() throws Exception {
        assertThat(links).isInstanceOf(FrontLinkBuilder.class);
        Identities.onSite(site, Identities.anonymous(), () -> {
            assertThat(links.home()).isEqualTo(HOME);
            assertThat(links.siteUrl()).isEqualTo(HOME);
            assertThat(links.admin("")).isEqualTo(HOME + "/wp-admin/");
            assertThat(links.rest("/wp/v2/posts")).isEqualTo(HOME + "/wp-json/wp/v2/posts");
            assertThat(links.post(snapshot(hello))).isEqualTo(HOME + "/2025/11/20/hello-world/");
            assertThat(links.postSample(snapshot(hello))).isEqualTo(HOME + "/2025/11/20/%postname%/");
            assertThat(links.term("category", news, "news")).isEqualTo(HOME + "/category/news/");
            assertThat(links.term("category", TermId.of(999999), "gone")).isEqualTo(HOME + "/?cat=999999");
            assertThat(links.term("genre", scifi, "scifi")).isEqualTo(HOME + "/genre/scifi/");
            assertThat(links.term("mood", TermId.of(999999), "gone")).isEqualTo(HOME + "/?taxonomy=mood&term=gone");
            assertThat(links.author(alice)).isEqualTo(HOME + "/author/alice-" + site.value() + "/");
            assertThat(links.author(UserId.of(999999))).isEqualTo(HOME + "/author/");
            assertThat(links.postTypeArchive("book")).contains(HOME + "/books/");
            assertThat(links.postTypeArchive("page")).isEmpty();
            assertThat(links.postFormat("aside")).isEqualTo(HOME + "/type/aside/");
            assertThat(links.date(2025, OptionalInt.of(3), OptionalInt.empty())).isEqualTo(HOME + "/2025/03/");
            assertThat(links.date(2025, OptionalInt.of(3), OptionalInt.of(7))).isEqualTo(HOME + "/2025/03/07/");
            assertThat(links.date(2025, OptionalInt.empty(), OptionalInt.empty()))
                    .isEqualTo(HOME + "/2025/");
            assertThat(links.search("java 21")).isEqualTo(HOME + "/search/java+21/");
            return null;
        });
        TestData.textOption(dataSource, site, "permalink_structure", "");
        Identities.onSite(site, Identities.anonymous(), () -> {
            assertThat(links.post(snapshot(hello))).isEqualTo(HOME + "/?p=" + hello.value());
            assertThat(links.term("genre", scifi, "scifi")).isEqualTo(HOME + "/?genre=scifi");
            assertThat(links.term("genre", TermId.of(999999), "gone")).isEqualTo(HOME + "/?genre=gone");
            assertThat(links.author(UserId.of(999999))).isEqualTo(HOME + "/?author=999999");
            assertThat(links.rest("/wp/v2/posts")).isEqualTo(HOME + "/index.php?rest_route=/wp/v2/posts");
            return null;
        });
    }

    @Test
    void aNewPostsGuidIsItsPermalink() throws Exception {
        List<PostSnapshot> created = Identities.onSite(
                site,
                Identities.user(alice),
                () -> List.of(
                        posts.create(PostChanges.builder()
                                .title("Hello")
                                .status("publish")
                                .date(LocalDateTime.of(2025, 11, 20, 10, 15, 30))
                                .build()),
                        posts.create(PostChanges.builder().title("Draft").build()),
                        posts.create(PostChanges.builder()
                                .type("page")
                                .title("About")
                                .status("publish")
                                .build()),
                        posts.create(PostChanges.builder()
                                .type("book")
                                .title("Dune")
                                .status("publish")
                                .build()),
                        posts.create(PostChanges.builder()
                                .type("book")
                                .title("Draft book")
                                .build())));
        assertThat(created.get(0).guid()).isEqualTo(HOME + "/2025/11/20/hello/");
        assertThat(created.get(1).guid())
                .isEqualTo(HOME + "/?p=" + created.get(1).id().value());
        assertThat(created.get(2).guid()).isEqualTo(HOME + "/about/");
        assertThat(created.get(3).guid()).isEqualTo(HOME + "/books/dune/");
        assertThat(created.get(4).guid())
                .isEqualTo(HOME + "/?post_type=book&p=" + created.get(4).id().value());
    }
}
