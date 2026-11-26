package org.quarkword.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.core.TermId;
import org.quarkword.core.UserId;
import org.quarkword.persistence.runtime.SiteDefaults;
import org.quarkword.posts.LinkBuilder;
import org.quarkword.posts.PostChanges;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.Posts;

class LinkBuilderDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(LinkBuilderDbTest.class, BookType.class);

    static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Inject
    LinkBuilder links;

    @Inject
    Posts posts;

    /** A site whose {@code home} and {@code siteurl} options are {@code address}, on a domain of its own. */
    private SiteId siteAt(String address) {
        return site("posts-home-" + UUID.randomUUID() + ".test", SiteDefaults.forNewSite("Blog", address));
    }

    private SiteId site(String domain, Map<String, JsonNode> options) {
        Jdbc.update(dataSource, "insert into qw_site (domain, path, name) values (?, '/', 'Blog')", domain);
        SiteId site = SiteId.of(Jdbc.count(dataSource, "select id from qw_site where domain = ?", domain));
        for (Map.Entry<String, JsonNode> option : options.entrySet()) {
            TestData.siteOption(
                    dataSource, site, option.getKey(), option.getValue().toString());
        }
        return site;
    }

    private static PostSnapshot snapshot(SiteId site, long id, String type, String status, String slug) {
        return new PostSnapshot(
                PostId.of(id),
                site,
                type,
                status,
                slug,
                "Title",
                "",
                "",
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.of(NOW),
                NOW,
                NOW,
                "open",
                "open",
                "",
                false,
                "",
                Map.of(),
                1);
    }

    private <T> T onSite(SiteId site, java.util.concurrent.Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.anonymous(), work);
    }

    @Test
    void theDefaultBuilderWritesPlainLinks() throws Exception {
        SiteId site = siteAt("http://posts.test");
        onSite(site, () -> {
            assertThat(links.home()).isEqualTo("http://posts.test");
            assertThat(links.siteUrl()).isEqualTo("http://posts.test");
            assertThat(links.post(snapshot(site, 7, "post", "publish", "hello")))
                    .isEqualTo("http://posts.test/?p=7");
            assertThat(links.post(snapshot(site, 21, "page", "publish", "about")))
                    .isEqualTo("http://posts.test/?page_id=21");
            assertThat(links.post(snapshot(site, 30, "attachment", "inherit", "photo")))
                    .isEqualTo("http://posts.test/?attachment_id=30");
            assertThat(links.post(snapshot(site, 40, "book", "publish", "dune")))
                    .isEqualTo("http://posts.test/?book=dune");
            assertThat(links.post(snapshot(site, 41, "book", "draft", "dune")))
                    .isEqualTo("http://posts.test/?post_type=book&p=41");
            assertThat(links.post(snapshot(site, 42, "book", "publish", "")))
                    .isEqualTo("http://posts.test/?post_type=book&p=42");
            assertThat(links.post(snapshot(site, 35, "wp_block", "publish", "pattern")))
                    .isEqualTo("http://posts.test/?post_type=wp_block&p=35");
            assertThat(links.postSample(snapshot(site, 7, "post", "publish", "hello")))
                    .isEqualTo("http://posts.test/?p=7");
            assertThat(links.term("category", TermId.of(4), "news")).isEqualTo("http://posts.test/?cat=4");
            assertThat(links.term("post_tag", TermId.of(9), "java")).isEqualTo("http://posts.test/?tag=java");
            assertThat(links.term("post_format", TermId.of(11), "post-format-aside"))
                    .isEqualTo("http://posts.test/?post_format=post-format-aside");
            assertThat(links.term("genre", TermId.of(12), "scifi"))
                    .isEqualTo("http://posts.test/?taxonomy=genre&term=scifi");
            assertThat(links.author(UserId.of(1))).isEqualTo("http://posts.test/?author=1");
            assertThat(links.postTypeArchive("book")).isEmpty();
            assertThat(links.postTypeArchive("post")).contains("http://posts.test");
            assertThat(links.postTypeArchive("page")).isEmpty();
            assertThat(links.postTypeArchive("unknown")).isEmpty();
            assertThat(links.postFormat("aside")).isEqualTo("http://posts.test/?post_format=aside");
            assertThat(links.date(2025, OptionalInt.of(11), OptionalInt.empty()))
                    .isEqualTo("http://posts.test/?m=202511");
            assertThat(links.date(2025, OptionalInt.of(3), OptionalInt.of(7)))
                    .isEqualTo("http://posts.test/?m=20250307");
            assertThat(links.date(2025, OptionalInt.empty(), OptionalInt.empty()))
                    .isEqualTo("http://posts.test/?m=2025");
            assertThat(links.search("java 21")).isEqualTo("http://posts.test/?s=java+21");
            assertThat(links.rest("wp/v2/posts")).isEqualTo("http://posts.test/index.php?rest_route=/wp/v2/posts");
            assertThat(links.rest("/wp/v2/posts")).isEqualTo("http://posts.test/index.php?rest_route=/wp/v2/posts");
            assertThat(links.rest("")).isEqualTo("http://posts.test/index.php?rest_route=/");
            assertThat(links.admin("")).isEqualTo("http://posts.test/wp-admin/");
            assertThat(links.admin("/post.php")).isEqualTo("http://posts.test/wp-admin/post.php");
            return null;
        });
        SiteId secure = siteAt("https://posts.test/blog");
        TestData.siteOption(dataSource, secure, "show_on_front", "\"page\"");
        TestData.siteOption(dataSource, secure, "page_for_posts", "21");
        onSite(secure, () -> {
            assertThat(links.home()).isEqualTo("https://posts.test/blog");
            assertThat(links.postTypeArchive("post")).contains("https://posts.test/blog/?page_id=21");
            assertThat(links.post(snapshot(secure, 7, "post", "publish", "hello")))
                    .isEqualTo("https://posts.test/blog/?p=7");
            // outside an https request, as WordPress's site_url and admin_url: the site URL is http, the admin URL
            // https because the site URL is stored with https
            assertThat(links.siteUrl()).isEqualTo("http://posts.test/blog");
            assertThat(links.admin("")).isEqualTo("https://posts.test/blog/wp-admin/");
            return null;
        });
    }

    @Test
    void theDefaultBuilderFallsBackToTheSiteRecord() throws Exception {
        SiteId site = site("posts.test", SiteDefaults.forNewSite("Blog"));
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qw_option where site_id = ? and name in ('home', 'siteurl')",
                        site.value()))
                .isZero();
        onSite(site, () -> {
            assertThat(links.home()).isEqualTo("http://posts.test");
            assertThat(links.siteUrl()).isEqualTo("http://posts.test");
            assertThat(links.post(snapshot(site, 7, "post", "publish", "hello")))
                    .isEqualTo("http://posts.test/?p=7");
            assertThat(links.admin("")).isEqualTo("http://posts.test/wp-admin/");
            return null;
        });
    }

    @Test
    void aNewPostsGuidIsItsPlainLink() throws Exception {
        SiteId site = siteAt("http://posts.test");
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        List<PostSnapshot> created = Identities.onSite(
                site,
                Identities.user(editor),
                () -> List.of(
                        posts.create(PostChanges.builder()
                                .title("Hello")
                                .status("publish")
                                .build()),
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
                                .build()),
                        posts.create(PostChanges.builder()
                                .type("book")
                                .title("!!!")
                                .content("A title that leaves no slug: the id is the slug")
                                .status("publish")
                                .build())));
        assertThat(created.get(0).guid())
                .isEqualTo("http://posts.test/?p=" + created.get(0).id().value());
        assertThat(created.get(1).guid())
                .isEqualTo("http://posts.test/?page_id=" + created.get(1).id().value());
        assertThat(created.get(2).guid()).isEqualTo("http://posts.test/?book=dune");
        assertThat(created.get(3).guid())
                .isEqualTo("http://posts.test/?post_type=book&p="
                        + created.get(3).id().value());
        assertThat(created.get(4).slug())
                .isEqualTo(Long.toString(created.get(4).id().value()));
        assertThat(created.get(4).guid())
                .isEqualTo("http://posts.test/?book=" + created.get(4).id().value());
    }
}
