package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostCreated;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostPublished;
import org.quarkpress.posts.PostSaved;
import org.quarkpress.posts.PostScheduled;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatusChanged;
import org.quarkpress.posts.Posts;

class CreateEventsDbTest {

    @ApplicationScoped
    static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();

        /** Read through a method: the injected client proxy does not share the bean's fields. */
        List<String> seen() {
            return seen;
        }

        void changed(@Observes PostStatusChanged event) {
            seen.add("status " + event.from() + ">" + event.to() + " "
                    + event.post().title());
        }

        void published(@Observes PostPublished event) {
            seen.add("published " + event.post().title());
        }

        void scheduled(@Observes PostScheduled event) {
            seen.add("scheduled " + event.post().title() + " at " + event.due());
        }

        void saved(@Observes PostSaved event) {
            if (event.post().title().equals("refuse")) {
                throw new PostException("observer_refused", "the observer refused the save");
            }
            seen.add("saved " + event.post().title() + " created=" + event.created());
        }

        void created(@Observes PostCreated event) {
            if (event.post().title().equals("explode")) {
                throw new IllegalStateException("observer refused");
            }
            seen.add("created " + event.post().title());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(CreateEventsDbTest.class, Recorder.class, TestClock.class);

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

    @Inject
    TransactionManager transactions;

    SiteId site;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        site = TestData.site(dataSource);
        recorder.seen().clear();
    }

    private PostSnapshot create(PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.system(), () -> posts.create(changes.build()));
    }

    @Test
    void aPublishedInsertFiresTransitionPublishSaveAndCreate() throws Exception {
        create(PostChanges.builder().title("one").status("publish"));
        assertThat(recorder.seen())
                .containsExactly("status new>publish one", "published one", "saved one created=true", "created one");
    }

    @Test
    void aScheduledInsertIsAnnouncedWithItsDueDate() throws Exception {
        Instant due = NOW.plus(Duration.ofDays(2));
        create(PostChanges.builder().title("two").status("publish").dateGmt(due));
        assertThat(recorder.seen())
                .containsExactly(
                        "status new>future two", "scheduled two at " + due, "saved two created=true", "created two");
    }

    @Test
    void aDraftInsertFiresTransitionSaveAndCreate() throws Exception {
        create(PostChanges.builder().title("three"));
        assertThat(recorder.seen())
                .containsExactly("status new>draft three", "saved three created=true", "created three");
    }

    @Test
    void anObserverThatThrowsRollsTheInsertBack() {
        assertThatThrownBy(() -> create(PostChanges.builder().title("explode")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("observer refused");
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post where site_id = ?", site.value()))
                .isZero();
    }

    @Test
    void anObserverRefusalTheCallerCatchesStillRollsTheInsertBack() {
        AtomicInteger status = new AtomicInteger(-1);
        assertThatThrownBy(() -> Identities.onSite(site, Identities.system(), () -> {
                    assertThatThrownBy(() -> posts.create(
                                    PostChanges.builder().title("refuse").build()))
                            .isInstanceOfSatisfying(
                                    PostException.class,
                                    e -> assertThat(e.code()).isEqualTo("observer_refused"))
                            .hasMessage("the observer refused the save");
                    status.set(transactions.getStatus());
                    return null;
                }))
                .isInstanceOf(QuarkusTransactionException.class)
                .hasCauseInstanceOf(RollbackException.class);
        assertThat(status.get()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post where site_id = ?", site.value()))
                .isZero();
    }

    @Test
    void anAttachmentInsertFiresNoneOfThesePostEvents() throws Exception {
        create(PostChanges.builder().type("attachment").title("file"));
        assertThat(recorder.seen()).isEmpty();
    }

    @Test
    void anUnregisteredTypeIsNotAnnouncedAsPublished() throws Exception {
        create(PostChanges.builder().type("thing").title("four").status("publish"));
        assertThat(recorder.seen())
                .containsExactly("status new>publish four", "saved four created=true", "created four");
    }
}
