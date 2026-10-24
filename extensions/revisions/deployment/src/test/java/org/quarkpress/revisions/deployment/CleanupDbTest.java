package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.revisions.RevisionDeleted;

/** A post's revisions go with the post, each announced; trash and untrash leave them alone. */
class CleanupDbTest {

    @ApplicationScoped
    static class Recorder {
        static final List<String> seen = new CopyOnWriteArrayList<>();

        @Inject
        Posts posts;

        /** The parent still exists when its revisions are announced as deleted. */
        void deleted(@Observes RevisionDeleted event) {
            seen.add(event.revision().title() + " parent="
                    + posts.find(event.revision().post()).isPresent());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(CleanupDbTest.class, Recorder.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Test
    void deletingAPostRemovesEveryRevisionAndAnnouncesEach() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        PostSnapshot post = Identities.onSite(
                site,
                Identities.user(editor),
                () -> posts.create(
                        PostChanges.builder().title("v0").status("publish").build()));
        for (int i = 1; i <= 30; i++) {
            String title = "v" + i;
            Identities.onSite(
                    site,
                    Identities.user(editor),
                    () -> posts.update(
                            post.id(), PostChanges.builder().title(title).build()));
        }
        for (int i = 0; i < 3; i++) {
            UserId user = TestData.profile(dataSource, "u" + i);
            TestData.revision(
                    dataSource, site, post.id(), user, "auto" + i, "", "", "{}", true, "2026-01-15T12:0" + i + ":00Z");
        }
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_revision where post_id = ?",
                        post.id().value()))
                .isEqualTo(33);
        Recorder.seen.clear();
        Identities.onSite(site, Identities.user(editor), () -> posts.trash(post.id()));
        Identities.onSite(site, Identities.user(editor), () -> posts.untrash(post.id()));
        assertThat(Recorder.seen).isEmpty();
        // the trash and the untrash changed no revisioned field: no revision was saved
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_revision where post_id = ?",
                        post.id().value()))
                .isEqualTo(33);
        Identities.onSite(site, Identities.user(editor), () -> posts.delete(post.id(), true));
        List<String> expected = new java.util.ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            expected.add("v" + i + " parent=true");
        }
        for (int i = 0; i < 3; i++) {
            expected.add("auto" + i + " parent=true");
        }
        assertThat(Recorder.seen).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_revision where post_id = ?",
                        post.id().value()))
                .isZero();
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_post where id = ?",
                        post.id().value()))
                .isZero();
    }

    @Test
    void anotherPostsRevisionsStay() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        PostId doomed = TestData.postBy(dataSource, site, editor, "page", "publish", "Doomed");
        PostId kept = TestData.postBy(dataSource, site, editor, "page", "publish", "Kept");
        TestData.revision(dataSource, site, doomed, editor, "d", "", "", "{}", false, "2026-01-15T12:00:00Z");
        TestData.revision(dataSource, site, kept, editor, "k", "", "", "{}", false, "2026-01-15T12:00:00Z");
        Identities.onSite(site, Identities.user(editor), () -> posts.delete(doomed, true));
        assertThat(Jdbc.strings(dataSource, "select title from qp_revision where site_id = ?", site.value()))
                .containsExactly("k");
    }
}
