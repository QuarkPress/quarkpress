package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;

/** Attachments are announced with events of their own, not as post saves: updating one saves no revision. */
class AttachmentRevisionsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(AttachmentRevisionsDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    TestClock clock;

    private SiteId site;
    private UserId editor;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
        editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
    }

    private PostSnapshot create(PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(editor), () -> posts.create(changes.build()));
    }

    private PostSnapshot update(PostId post, PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(editor), () -> posts.update(post, changes.build()));
    }

    private long revisions(PostId post) {
        return Jdbc.count(dataSource, "select count(*) from qp_revision where post_id = ?", post.value());
    }

    @Test
    void updatingAnAttachmentSavesNoRevision() throws Exception {
        PostSnapshot file = create(PostChanges.builder()
                .type("attachment")
                .title("v1")
                .content("one")
                .excerpt("caption"));
        clock.set(Instant.parse("2026-01-15T12:01:00Z"));
        update(file.id(), PostChanges.builder().title("v2").content("two"));
        clock.set(Instant.parse("2026-01-15T12:02:00Z"));
        update(file.id(), PostChanges.builder().excerpt("changed"));
        assertThat(revisions(file.id())).isZero();
        // a post updated the same way does get revisions: the zero above is not a broken setup
        PostSnapshot post =
                create(PostChanges.builder().title("v1").content("one").status("publish"));
        update(post.id(), PostChanges.builder().title("v2").content("two"));
        assertThat(revisions(post.id())).isEqualTo(1);
    }
}
