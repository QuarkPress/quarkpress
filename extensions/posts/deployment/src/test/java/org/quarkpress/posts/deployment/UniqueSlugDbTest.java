package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.SiteScopedNativeSql;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSlugFilter;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.SlugContext;

/**
 * {@code Posts.uniqueSlug} answers the slug a post would get, as a save would make it unique and filter it, without
 * writing and without locking.
 */
class UniqueSlugDbTest {

    /** A slug filter that marks the slugs it is asked about, as the write path's filters would. */
    @ApplicationScoped
    static class Suffix implements PostSlugFilter {
        @Override
        public String apply(String uniqueSlug, SlugContext context) {
            return context.original().startsWith("slugfilter") ? uniqueSlug + "-x" : uniqueSlug;
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(UniqueSlugDbTest.class, Suffix.class, RowLock.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    SiteId site;
    UserId editor;

    @BeforeEach
    void setUp() {
        site = TestData.site(dataSource);
        // a structure that starts with the post name: an all-digit slug would read as a date archive
        TestData.siteOption(dataSource, site, "permalink_structure", "\"/%postname%/\"");
        editor = TestData.memberOfMain(dataSource, "editor");
    }

    private PostSnapshot create(PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(editor), () -> posts.create(changes.build()));
    }

    private String uniqueSlug(String type, String slug, Optional<PostId> post, Optional<PostId> parent, String status)
            throws Exception {
        return Identities.onSite(
                site, Identities.anonymous(), () -> posts.uniqueSlug(type, slug, post, parent, status));
    }

    @Test
    void publishedNamespace() throws Exception {
        PostSnapshot hello = create(PostChanges.builder().title("Hello").status("publish"));
        assertThat(hello.slug()).isEqualTo("hello");
        // another post would get the next free suffix, as a published post would
        assertThat(uniqueSlug("post", "hello", Optional.empty(), Optional.empty(), "publish"))
                .isEqualTo("hello-2");
        // a draft keeps its slug as a save keeps it
        assertThat(uniqueSlug("post", "hello", Optional.empty(), Optional.empty(), "draft"))
                .isEqualTo("hello");
        // a numeric slug reads as a date archive under this structure
        assertThat(uniqueSlug("post", "38", Optional.empty(), Optional.empty(), "publish"))
                .isEqualTo("38-2");
        // the post's own stored slug is not a collision
        assertThat(uniqueSlug("post", "hello", Optional.of(hello.id()), Optional.empty(), "publish"))
                .isEqualTo("hello");
        // another type has its own namespace; nothing was written
        assertThat(uniqueSlug("page", "hello", Optional.empty(), Optional.empty(), "publish"))
                .isEqualTo("hello");
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_post where site_id = ? and slug like 'hello%'",
                        site.value()))
                .isEqualTo(1);
    }

    @Test
    void slugFiltersRun() throws Exception {
        assertThat(uniqueSlug("post", "slugfilter-hello", Optional.empty(), Optional.empty(), "publish"))
                .isEqualTo("slugfilter-hello-x");
        assertThat(uniqueSlug("post", "plain", Optional.empty(), Optional.empty(), "publish"))
                .isEqualTo("plain");
    }

    @Test
    void noLockIsTaken() throws Exception {
        PostSnapshot hello = create(PostChanges.builder().title("Locked").status("publish"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        // a writer holds the row's exclusive lock in a transaction of its own
        try (RowLock writer = new RowLock(dataSource, site, hello.id())) {
            // a reader asking what slug the next post would get does not wait for the writer
            Future<String> read =
                    pool.submit(() -> uniqueSlug("post", "locked", Optional.empty(), Optional.empty(), "publish"));
            assertThat(read.get(1, TimeUnit.SECONDS)).isEqualTo("locked-2");
        } finally {
            pool.shutdownNow();
        }
    }

    /** A connection of its own that holds a post row's exclusive lock until closed (then rolls back). */
    @SiteScopedNativeSql
    static final class RowLock implements AutoCloseable {

        private final Connection connection;

        RowLock(AgroalDataSource dataSource, SiteId site, PostId post) throws SQLException {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            try (PreparedStatement select =
                    connection.prepareStatement("select id from qp_post where site_id = ? and id = ? for update")) {
                select.setLong(1, site.value());
                select.setLong(2, post.value());
                try (ResultSet rows = select.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalStateException("no post " + post.value() + " on site " + site.value());
                    }
                }
            }
        }

        @Override
        public void close() throws SQLException {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }
}
