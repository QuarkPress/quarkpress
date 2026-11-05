package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.AttachmentAdded;
import org.quarkpress.posts.AttachmentEdited;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostCreated;
import org.quarkpress.posts.PostDeleted;
import org.quarkpress.posts.PostDeleting;
import org.quarkpress.posts.PostPublished;
import org.quarkpress.posts.PostRestored;
import org.quarkpress.posts.PostSaved;
import org.quarkpress.posts.PostScheduled;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatusChanged;
import org.quarkpress.posts.PostTrashed;
import org.quarkpress.posts.PostUpdated;
import org.quarkpress.posts.Posts;

/**
 * Creating or updating an attachment announces it with the attachment's own events, as WordPress's upload logs
 * {@code add_attachment} and its update {@code edit_attachment} and {@code attachment_updated}, never
 * {@code save_post}, {@code transition_post_status} or {@code wp_insert_post}; other types are announced as before.
 */
class AttachmentEventsDbTest {

    /** Records every post event in the order it is fired, synchronously, and the attachment events after commit. */
    @ApplicationScoped
    static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();
        final List<String> late = new CopyOnWriteArrayList<>();

        /** Read through methods: the injected client proxy does not share the bean's fields. */
        List<String> seen() {
            return seen;
        }

        List<String> late() {
            return late;
        }

        void changed(@Observes PostStatusChanged event) {
            seen.add("PostStatusChanged " + event.from() + ">" + event.to());
        }

        void published(@Observes PostPublished event) {
            seen.add("PostPublished");
        }

        void scheduled(@Observes PostScheduled event) {
            seen.add("PostScheduled");
        }

        void updated(@Observes PostUpdated event) {
            seen.add("PostUpdated");
        }

        void saved(@Observes PostSaved event) {
            seen.add("PostSaved");
        }

        void created(@Observes PostCreated event) {
            seen.add("PostCreated");
        }

        void trashed(@Observes PostTrashed event) {
            seen.add("PostTrashed");
        }

        void restored(@Observes PostRestored event) {
            seen.add("PostRestored");
        }

        void deleting(@Observes PostDeleting event) {
            seen.add("PostDeleting " + event.post().title());
        }

        void deleted(@Observes PostDeleted event) {
            seen.add("PostDeleted " + event.type());
        }

        void added(@Observes AttachmentAdded event) {
            seen.add("AttachmentAdded " + event.post().type() + " "
                    + event.post().title());
        }

        void edited(@Observes AttachmentEdited event) {
            seen.add("AttachmentEdited " + event.before().title() + ">"
                    + event.after().title());
        }

        void addedLate(@ObservesAsync AttachmentAdded event) {
            late.add("AttachmentAdded " + event.post().title());
        }

        void editedLate(@ObservesAsync AttachmentEdited event) {
            late.add("AttachmentEdited " + event.after().title());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app =
            PostsApp.with(AttachmentEventsDbTest.class, Recorder.class, TestClock.class);

    static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    Recorder recorder;

    @Inject
    TestClock clock;

    SiteId site;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        site = TestData.site(dataSource);
        recorder.seen().clear();
        recorder.late().clear();
    }

    private <T> T as(Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.system(), work);
    }

    private PostSnapshot create(PostChanges.Builder changes) throws Exception {
        return as(() -> posts.create(changes.build()));
    }

    /** Whether an asynchronous observer heard {@code entry} within ten seconds. */
    private boolean heardAfterCommit(String entry) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (recorder.late().contains(entry)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    void creatingAnAttachmentFiresOnlyAttachmentAdded() throws Exception {
        PostSnapshot file = create(PostChanges.builder().type("attachment").title("added"));
        assertThat(recorder.seen()).containsExactly("AttachmentAdded attachment added");
        assertThat(file.status()).isEqualTo("inherit");
        assertThat(heardAfterCommit("AttachmentAdded added")).isTrue();
    }

    @Test
    void updatingAnAttachmentFiresOnlyAttachmentEdited() throws Exception {
        PostSnapshot file = create(PostChanges.builder().type("attachment").title("a"));
        recorder.seen().clear();
        as(() -> posts.update(file.id(), PostChanges.builder().title("b").build()));
        assertThat(recorder.seen()).containsExactly("AttachmentEdited a>b");
        assertThat(heardAfterCommit("AttachmentEdited b")).isTrue();
    }

    @Test
    void anUpdateThatChangesNothingIsStillAnnouncedAsEdited() throws Exception {
        PostSnapshot file = create(PostChanges.builder().type("attachment").title("same"));
        recorder.seen().clear();
        as(() -> posts.update(file.id(), PostChanges.builder().build()));
        assertThat(recorder.seen()).containsExactly("AttachmentEdited same>same");
    }

    @Test
    void otherTypesAreUnchanged() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("p").status("draft"));
        assertThat(recorder.seen()).containsExactly("PostStatusChanged new>draft", "PostSaved", "PostCreated");
        recorder.seen().clear();
        as(() -> posts.update(post.id(), PostChanges.builder().title("q").build()));
        assertThat(recorder.seen()).containsExactly("PostStatusChanged draft>draft", "PostUpdated", "PostSaved");
    }

    @Test
    void publishingAndTrashingAnAttachmentFireWhatTheyFiredBefore() throws Exception {
        PostSnapshot file = create(PostChanges.builder().type("attachment").title("f"));
        recorder.seen().clear();
        assertThat(as(() -> posts.publish(file.id()))).isTrue();
        assertThat(recorder.seen()).containsExactly("PostStatusChanged inherit>publish", "PostPublished", "PostSaved");
        recorder.seen().clear();
        as(() -> posts.trash(file.id()));
        as(() -> posts.untrash(file.id()));
        assertThat(recorder.seen()).containsExactly("PostTrashed", "PostRestored");
    }

    @Test
    void deletingAnAttachmentStillAnnouncesTheDelete() throws Exception {
        PostSnapshot file = create(PostChanges.builder().type("attachment").title("gone"));
        recorder.seen().clear();
        as(() -> posts.delete(file.id(), true));
        assertThat(recorder.seen()).containsExactly("PostDeleting gone", "PostDeleted attachment");
    }
}
