package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.revisions.Revisions;

/** A restore records the restoring user as the post's last editor; a posts update does not. */
class RestoreEditLastDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(RestoreEditLastDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    Revisions revisions;

    @Inject
    TestClock clock;

    @Test
    void aRestoreWritesTheRestoringUserAsLastEditorAndAnUpdateWritesNone() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId carol = TestData.profile(dataSource, "carol");
        TestData.member(dataSource, TestData.MAIN, carol, "[\"editor\"]");
        UserId bob = TestData.profile(dataSource, "bob");
        TestData.member(dataSource, TestData.MAIN, bob, "[\"editor\"]");
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
        PostId post = TestData.postBy(dataSource, site, carol, "post", "publish", "Post");
        RevisionId r8 = TestData.revision(
                dataSource, site, post, carol, "Older", "older", "", "{}", false, "2026-01-15T10:00:00Z");
        PostSnapshot updated = Identities.onSite(
                site,
                Identities.user(carol),
                () -> posts.update(post, PostChanges.builder().title("Newer").build()));
        assertThat(updated.meta("_edit_last")).isEmpty();
        PostSnapshot restored = Identities.onSite(site, Identities.user(bob), () -> revisions.restore(r8, Set.of()));
        assertThat(restored.title()).isEqualTo("Older");
        assertThat(restored.metaText("_edit_last")).contains(Long.toString(bob.value()));
        assertThat(Identities.onSite(site, Identities.user(bob), () -> posts.find(post))
                        .orElseThrow()
                        .metaText("_edit_last"))
                .contains(Long.toString(bob.value()));
        // stored as WordPress stores it: the user id as text, like the lock's value
        assertThat(Jdbc.strings(dataSource, "select meta from qp_post where id = ?", post.value()))
                .singleElement()
                .satisfies(meta -> assertThat(meta).contains("\"_edit_last\":[\"" + bob.value() + "\"]"));
        // the revision the restore saved does not carry the key, and a later update leaves it as it is
        PostSnapshot again = Identities.onSite(
                site,
                Identities.user(carol),
                () -> posts.update(post, PostChanges.builder().title("Newest").build()));
        assertThat(again.metaText("_edit_last")).contains(Long.toString(bob.value()));
        assertThat(Jdbc.strings(
                        dataSource,
                        "select meta from qp_revision where post_id = ? and autosave = false",
                        post.value()))
                .noneMatch(meta -> meta.contains("_edit_last"));
    }
}
