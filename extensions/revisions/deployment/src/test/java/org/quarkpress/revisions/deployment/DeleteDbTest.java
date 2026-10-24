package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.Posts;
import org.quarkpress.revisions.RevisionDeleted;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.revisions.Revisions;

class DeleteDbTest {

    @ApplicationScoped
    static class Recorder {
        static final List<String> seen = new CopyOnWriteArrayList<>();

        void deleted(@Observes RevisionDeleted event) {
            seen.add("deleted " + event.revision().title());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(DeleteDbTest.class, Recorder.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Revisions revisions;

    @Inject
    Posts posts;

    @Test
    void theSystemDeletesARevisionAndAnnouncesIt() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        PostId post = TestData.postBy(dataSource, site, editor, "post", "publish", "P");
        RevisionId kept =
                TestData.revision(dataSource, site, post, editor, "kept", "", "", "{}", false, "2026-01-15T12:00:00Z");
        RevisionId gone =
                TestData.revision(dataSource, site, post, editor, "gone", "", "", "{}", true, "2026-01-15T12:01:00Z");
        Recorder.seen.clear();
        Optional<RevisionSnapshot> deleted = Identities.onSite(site, Identities.system(), () -> revisions.delete(gone));
        assertThat(deleted.map(RevisionSnapshot::title)).contains("gone");
        assertThat(Recorder.seen).containsExactly("deleted gone");
        assertThat(Jdbc.strings(dataSource, "select title from qp_revision where post_id = ?", post.value()))
                .containsExactly("kept");
        assertThat(Identities.onSite(site, Identities.system(), () -> revisions.delete(gone)))
                .isEmpty();
        assertThat(Identities.onSite(site, Identities.system(), () -> revisions.delete(RevisionId.of(999_999_999L))))
                .isEmpty();
        SiteId other = TestData.site(dataSource);
        assertThat(Identities.onSite(other, Identities.system(), () -> revisions.delete(kept)))
                .isEmpty();
        assertThat(Recorder.seen).containsExactly("deleted gone");
    }

    @Test
    void deletingARevisionOfATrashedPostLeavesTheTrashRecordsAlone() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        PostId post = Identities.onSite(
                        site,
                        Identities.user(editor),
                        () -> posts.create(org.quarkpress.posts.PostChanges.builder()
                                .title("T")
                                .status("publish")
                                .build()))
                .id();
        Identities.onSite(site, Identities.user(editor), () -> posts.trash(post));
        long before = Jdbc.count(dataSource, "select count(*) from qp_revision where post_id = ?", post.value());
        RevisionId revision = Identities.onSite(
                        site,
                        Identities.user(editor),
                        () -> revisions.ofPost(post, org.quarkpress.revisions.RevisionQuery.all()))
                .get(0)
                .id();
        assertThat(Identities.onSite(site, Identities.system(), () -> revisions.delete(revision)))
                .isPresent();
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_revision where post_id = ?", post.value()))
                .isEqualTo(before - 1);
        assertThat(Jdbc.strings(dataSource, "select meta from qp_post where id = ?", post.value())
                        .get(0))
                .contains("_wp_trash_meta_status");
        // untrash returns the snapshot from before the untrash; the stored status is checked below
        assertThat(Identities.onSite(site, Identities.user(editor), () -> posts.untrash(post))
                        .map(p -> p.status()))
                .contains("trash");
        assertThat(Jdbc.strings(dataSource, "select status from qp_post where id = ?", post.value()))
                .containsExactly("draft");
    }
}
