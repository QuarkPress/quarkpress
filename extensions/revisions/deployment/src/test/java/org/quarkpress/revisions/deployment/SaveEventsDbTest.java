package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSaved;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.revisions.RevisionCreated;
import org.quarkpress.revisions.RevisionDeleted;

/** Trimming with a limit of 2, the events of a save, and what an observer sees. */
class SaveEventsDbTest {

    @ApplicationScoped
    static class Recorder {
        /** Static: the injected recorder is a client proxy, whose own fields are not the bean's. */
        static final List<String> seen = new CopyOnWriteArrayList<>();

        void created(@Observes RevisionCreated event) {
            if (event.revision().title().equals("explode")) {
                throw new IllegalStateException("observer refused");
            }
            seen.add("created " + event.revision().title() + " at " + event.at());
        }

        void deleted(@Observes RevisionDeleted event) {
            seen.add("deleted " + event.revision().title());
        }

        /** An application observer of the save sees the revision already written. */
        void saved(@Observes PostSaved event, RevisionsCount count) {
            seen.add("saved " + event.post().title() + " revisions="
                    + count.of(event.site(), event.post().id()));
        }
    }

    /** Counts through the module's repository: the row of this save is not yet committed for another connection. */
    @ApplicationScoped
    static class RevisionsCount {
        @Inject
        org.quarkpress.revisions.runtime.RevisionRepository revisions;

        long of(SiteId site, PostId post) {
            return revisions.countOfPost(site, post);
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(
                    SaveEventsDbTest.class, Recorder.class, RevisionsCount.class)
            .overrideConfigKey("quarkpress.revisions.keep", "2");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    TestClock clock;

    @Inject
    TransactionManager transactions;

    private SiteId site;
    private UserId editor;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
        editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        Recorder.seen.clear();
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
    }

    private PostSnapshot update(PostId post, String title) throws Exception {
        clock.set(clock.now().plusSeconds(60));
        return Identities.onSite(
                site,
                Identities.user(editor),
                () -> posts.update(post, PostChanges.builder().title(title).build()));
    }

    private List<String> titles(PostId post) {
        return Jdbc.strings(
                dataSource, "select title from qp_revision where post_id = ? order by created_at, id", post.value());
    }

    @Test
    void theOldestRegularRevisionsGoAndAutosavesAreSkippedWithoutReplacement() throws Exception {
        PostSnapshot post = Identities.onSite(
                site,
                Identities.user(editor),
                () -> posts.create(
                        PostChanges.builder().title("v1").status("publish").build()));
        update(post.id(), "v2");
        update(post.id(), "v3");
        assertThat(titles(post.id())).containsExactly("v2", "v3");
        update(post.id(), "v4");
        assertThat(titles(post.id())).containsExactly("v3", "v4");
        assertThat(Recorder.seen)
                .containsSubsequence(
                        "created v2 at 2026-01-15T12:01:00Z",
                        "saved v2 revisions=1",
                        "created v4 at 2026-01-15T12:03:00Z",
                        "deleted v2",
                        "saved v4 revisions=2");
        // an autosave older than every regular revision is a candidate that is skipped, not replaced
        TestData.revision(dataSource, site, post.id(), editor, "auto", "", "", "{}", true, "2026-01-15T11:00:00Z");
        update(post.id(), "v5");
        assertThat(titles(post.id())).containsExactly("auto", "v4", "v5");
        update(post.id(), "v6");
        assertThat(titles(post.id())).containsExactly("auto", "v5", "v6");
        // three regular revisions and a newer autosave: with keep 2 the three go, one regular revision remains
        Jdbc.update(
                dataSource,
                "update qp_revision set created_at = " + TestDb.instant() + " where title = 'auto'",
                TestDb.instantText("2026-01-15T13:00:00Z"));
        TestData.revision(dataSource, site, post.id(), null, "v0", "", "", "{}", false, "2026-01-15T11:30:00Z");
        update(post.id(), "v7");
        assertThat(titles(post.id())).containsExactly("v7", "auto");
    }

    @Test
    void anObserverThatThrowsUndoesTheWholeSave() throws Exception {
        PostSnapshot post = Identities.onSite(
                site,
                Identities.user(editor),
                () -> posts.create(
                        PostChanges.builder().title("v1").status("publish").build()));
        assertThatThrownBy(() -> update(post.id(), "explode")).hasMessageContaining("observer refused");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select title from qp_post where id = ?",
                        post.id().value()))
                .containsExactly("v1");
        assertThat(titles(post.id())).isEmpty();
    }

    @Test
    void aSaveRefusedBeforeWritingLeavesTheTransactionUsableAndAFailureAfterMarksIt() throws Exception {
        PostSnapshot post = Identities.onSite(
                site,
                Identities.user(editor),
                () -> posts.create(
                        PostChanges.builder().title("v1").status("publish").build()));
        try {
            Identities.onSite(site, Identities.user(editor), () -> {
                assertThatThrownBy(() -> posts.update(
                                PostId.of(999_999_999L),
                                PostChanges.builder().title("x").build()))
                        .isInstanceOf(org.quarkpress.posts.PostException.class);
                assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
                assertThatThrownBy(() -> posts.update(
                                post.id(),
                                PostChanges.builder().title("explode").build()))
                        .hasMessageContaining("observer refused");
                assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
                return null;
            });
        } catch (Exception rolledBack) {
            // the scope's transaction was marked above and rolls back when it ends; an AssertionError is not caught
        }
        assertThat(titles(post.id())).isEmpty();
    }
}
