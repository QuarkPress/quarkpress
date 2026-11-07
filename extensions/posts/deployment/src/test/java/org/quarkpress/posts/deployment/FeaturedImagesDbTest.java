package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.SiteScopedNativeSql;
import org.quarkpress.posts.FeaturedImages;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.runtime.FeaturedImageReferences;
import org.quarkpress.posts.runtime.JpaFeaturedImages;
import org.quarkpress.posts.runtime.PostLocks;

/** The featured image ({@code _thumbnail_id}) as WordPress's REST write sets it. */
class FeaturedImagesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(FeaturedImagesDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    FeaturedImages images;

    @Inject
    Posts posts;

    @Inject
    JpaFeaturedImages featuredImages;

    @Inject
    PostLocks postLocks;

    @Inject
    FeaturedImageReferences references;

    @Test
    void setFollowsWordPress() throws Exception {
        SiteId site = TestData.site(dataSource);
        SiteId other = TestData.site(dataSource);
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        UserId erin = TestData.memberOfMain(dataSource, "subscriber");
        PostId post = TestData.post(dataSource, site, "post", "draft", "", "Pictured");
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", editor.value(), post.value());
        PostId attachment = TestData.post(dataSource, site, "attachment", "inherit", "", "Picture");
        PostId page = TestData.post(dataSource, site, "page", "publish", "about", "About");
        PostId elsewhere = TestData.post(dataSource, other, "attachment", "inherit", "", "Elsewhere");
        List<String> before = versionAndModified(post);

        assertThat(of(site, editor, post)).isZero();
        set(site, editor, post, attachment.value());
        assertThat(of(site, editor, post)).isEqualTo(attachment.value());
        set(site, editor, post, 999_999);
        assertThat(of(site, editor, post)).isEqualTo(attachment.value());
        set(site, editor, post, elsewhere.value());
        assertThat(of(site, editor, post)).isEqualTo(attachment.value());
        set(site, editor, post, page.value());
        assertThat(of(site, editor, post)).isZero();
        assertThat(Jdbc.strings(dataSource, "select meta from qp_post where id = ?", post.value()))
                .containsExactly("{}");
        set(site, editor, post, -attachment.value());
        assertThat(of(site, editor, post)).isEqualTo(attachment.value());
        set(site, editor, post, 0);
        assertThat(of(site, editor, post)).isZero();
        assertThat(versionAndModified(post)).isEqualTo(before);

        assertThatThrownBy(() -> set(site, erin, post, attachment.value()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability edit_post on site " + site.value());
    }

    @Test
    void forgetRemovesEveryReferenceToTheAttachment() throws Exception {
        SiteId site = TestData.site(dataSource);
        SiteId other = TestData.site(dataSource);
        PostId attachment = TestData.post(dataSource, site, "attachment", "inherit", "", "Picture");
        PostId kept = TestData.post(dataSource, site, "attachment", "inherit", "", "Kept");
        long id = attachment.value();
        long k = kept.value();
        // the id as a number, as its decimal text, beside other values; values that only look like it; another site
        PostId asNumber = postNaming(site, "[" + id + "]");
        PostId asText = postNaming(site, "[\"" + id + "\"]");
        PostId otherAttachment = postNaming(site, "[" + k + "]");
        PostId padded = postNaming(site, "[\"0" + id + "\"]");
        PostId decimal = postNaming(site, "[" + id + ".0]");
        PostId secondValue = postNaming(site, "[" + k + ", " + id + "]");
        PostId firstValue = postNaming(site, "[" + id + ", " + k + "]");
        PostId twice = postNaming(site, "[" + id + ", \"" + id + "\"]");
        PostId elsewhere = postNaming(other, "[" + id + "]");
        List<String> numberBefore = versionAndModified(asNumber);
        List<String> textBefore = versionAndModified(asText);
        List<String> secondBefore = versionAndModified(secondValue);

        // the query names the site: another site's post naming the same id is no candidate
        List<Long> candidates = Identities.onSite(
                site,
                Identities.system(),
                () -> QuarkusTransaction.requiringNew().call(() -> references.postsNaming(site, id)));
        assertThat(candidates)
                .containsExactly(
                        asNumber.value(), asText.value(), secondValue.value(), firstValue.value(), twice.value());
        assertThat(Identities.onSite(
                        other,
                        Identities.system(),
                        () -> QuarkusTransaction.requiringNew().call(() -> references.postsNaming(other, id))))
                .containsExactly(elsewhere.value());

        forget(site, attachment);

        assertThat(meta(asNumber)).isEqualTo("{\"other\":[\"x\"]}");
        assertThat(meta(asText)).isEqualTo("{\"other\":[\"x\"]}");
        assertThat(meta(twice)).isEqualTo("{\"other\":[\"x\"]}");
        // only the values naming the attachment go, as WordPress deletes only the meta rows holding its id
        assertThat(meta(secondValue)).isEqualTo("{\"_thumbnail_id\":[" + k + "],\"other\":[\"x\"]}");
        assertThat(meta(firstValue)).isEqualTo("{\"_thumbnail_id\":[" + k + "],\"other\":[\"x\"]}");
        assertThat(meta(otherAttachment)).isEqualTo("{\"_thumbnail_id\":[" + k + "],\"other\":[\"x\"]}");
        assertThat(meta(padded)).isEqualTo("{\"_thumbnail_id\":[\"0" + id + "\"],\"other\":[\"x\"]}");
        assertThat(meta(decimal)).contains("\"_thumbnail_id\"");
        assertThat(meta(elsewhere)).isEqualTo("{\"_thumbnail_id\":[" + id + "],\"other\":[\"x\"]}");
        // the meta alone changes: neither the version nor the modification date
        assertThat(versionAndModified(asNumber)).isEqualTo(numberBefore);
        assertThat(versionAndModified(asText)).isEqualTo(textBefore);
        assertThat(versionAndModified(secondValue)).isEqualTo(secondBefore);
    }

    @Test
    void forgetDecidesOnThePostAsItIsUnderTheLock() throws Exception {
        SiteId site = TestData.site(dataSource);
        PostId attachment = TestData.post(dataSource, site, "attachment", "inherit", "", "Picture");
        PostId kept = TestData.post(dataSource, site, "attachment", "inherit", "", "Kept");
        PostId post = postNaming(site, "[" + attachment.value() + "]");
        String changed = "{\"_thumbnail_id\":[" + kept.value() + "],\"other\":[\"x\"]}";

        // another writer holds the post, changing its featured image, while forget finds it by the committed value
        try (UncommittedMeta writer = new UncommittedMeta(dataSource, site, post, changed)) {
            // a thread made here keeps the application's class loader, as the forget needs it
            ExecutorService thread = Executors.newSingleThreadExecutor();
            try {
                Future<Object> forgetting = thread.submit(() -> {
                    forget(site, attachment);
                    return null;
                });
                Thread.sleep(1500);
                assertThat(forgetting).isNotDone();
                writer.commit();
                forgetting.get(60, TimeUnit.SECONDS);
            } finally {
                thread.shutdownNow();
            }
        }

        assertThat(meta(post)).isEqualTo(changed);
    }

    /** A connection of its own that has written a post's meta and holds its row lock until it commits. */
    @SiteScopedNativeSql
    static final class UncommittedMeta implements AutoCloseable {

        private final Connection connection;

        UncommittedMeta(AgroalDataSource dataSource, SiteId site, PostId post, String meta) throws SQLException {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(
                    "update qp_post set meta = " + TestDb.json() + " where site_id = ? and id = ?")) {
                update.setString(1, meta);
                update.setLong(2, site.value());
                update.setLong(3, post.value());
                update.executeUpdate();
            }
        }

        void commit() throws SQLException {
            connection.commit();
        }

        @Override
        public void close() throws SQLException {
            connection.close();
        }
    }

    private void forget(SiteId site, PostId attachment) throws Exception {
        Identities.onSite(site, Identities.system(), () -> {
            QuarkusTransaction.requiringNew().run(() -> {
                postLocks.lock(site);
                featuredImages.forget(site, attachment);
            });
            return null;
        });
    }

    /** A post of {@code site} whose {@code _thumbnail_id} values are the JSON array {@code values}. */
    private PostId postNaming(SiteId site, String values) {
        PostId post = TestData.post(dataSource, site, "post", "publish", "", "Pictured");
        Jdbc.update(
                dataSource,
                "update qp_post set meta = " + TestDb.json() + " where id = ?",
                "{\"_thumbnail_id\":" + values + ",\"other\":[\"x\"]}",
                post.value());
        return post;
    }

    private String meta(PostId post) {
        return Jdbc.strings(dataSource, "select meta from qp_post where id = ?", post.value())
                .get(0)
                .replace(" ", "");
    }

    private void set(SiteId site, UserId user, PostId post, long attachment) throws Exception {
        Identities.onSite(site, Identities.user(user), () -> {
            images.set(post, attachment);
            return null;
        });
    }

    private long of(SiteId site, UserId user, PostId post) throws Exception {
        return Identities.onSite(
                site,
                Identities.user(user),
                () -> FeaturedImages.of(posts.find(post).orElseThrow()));
    }

    private List<String> versionAndModified(PostId post) {
        return List.of(
                Jdbc.strings(dataSource, "select version from qp_post where id = ?", post.value())
                        .get(0),
                Jdbc.strings(dataSource, "select modified_at from qp_post where id = ?", post.value())
                        .get(0));
    }
}
