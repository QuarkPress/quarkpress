package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostPublished;
import org.quarkpress.posts.PostSaved;
import org.quarkpress.posts.PostScheduled;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatusChanged;
import org.quarkpress.posts.PostUpdated;
import org.quarkpress.posts.Posts;

/** Publishing changes only the status, as wp_publish_post does; updates and publishing announce themselves. */
class PublishDbTest {

    @ApplicationScoped
    static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();

        /** Read through a method: the injected client proxy does not share the bean's fields. */
        List<String> seen() {
            return seen;
        }

        void changed(@Observes PostStatusChanged event) {
            seen.add("status " + event.from() + ">" + event.to());
        }

        void published(@Observes PostPublished event) {
            seen.add("published");
        }

        void scheduled(@Observes PostScheduled event) {
            seen.add("scheduled " + event.due());
        }

        void updated(@Observes PostUpdated event) {
            if (event.after().title().equals("refuse")) {
                throw new PostException("observer_refused", "the observer refused the update");
            }
            seen.add("updated " + event.before().title() + ">" + event.after().title());
        }

        void saved(@Observes PostSaved event) {
            seen.add("saved created=" + event.created());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(PublishDbTest.class, Recorder.class, TestClock.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    TestClock clock;

    @Inject
    Recorder recorder;

    @Inject
    TransactionManager transactions;

    SiteId site;
    UserId editor;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
        site = TestData.site(dataSource);
        editor = TestData.memberOfMain(dataSource, "editor");
    }

    private <T> T as(UserId user, Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.user(user), work);
    }

    private <T> T as(SecurityIdentity identity, Callable<T> work) throws Exception {
        return Identities.onSite(site, identity, work);
    }

    private PostSnapshot scheduledAt(String gmt) throws Exception {
        PostSnapshot post = create(
                PostChanges.builder().title("scheduled").status("publish").dateGmt(Instant.parse(gmt)));
        assertThat(post.status()).isEqualTo("future");
        return post;
    }

    private PostSnapshot create(PostChanges.Builder changes) throws Exception {
        return as(editor, () -> posts.create(changes.build()));
    }

    @Test
    void publishingChangesOnlyTheStatus() throws Exception {
        PostSnapshot draft = create(PostChanges.builder().title("Hello"));
        clock.set(Instant.parse("2026-01-16T12:00:00Z"));
        assertThat(as(editor, () -> posts.publish(draft.id()))).isTrue();
        PostSnapshot published = as(editor, () -> posts.find(draft.id())).orElseThrow();
        assertThat(published.status()).isEqualTo("publish");
        assertThat(published.slug()).isEmpty();
        assertThat(published.publishedAt()).isEmpty();
        assertThat(published.modifiedAt()).isEqualTo(draft.modifiedAt());
        assertThat(published.title()).isEqualTo(draft.title());
        assertThat(as(editor, () -> posts.publish(draft.id()))).isFalse();
    }

    @Test
    void publishingAMissingPostIsInvalid() {
        assertThatThrownBy(() -> as(editor, () -> posts.publish(PostId.of(Long.MAX_VALUE))))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"))
                .hasMessage("Invalid post ID.");
    }

    @Test
    void contributorsCannotPublish() throws Exception {
        UserId contributor = TestData.memberOfMain(dataSource, "contributor");
        PostSnapshot draft = as(
                contributor,
                () -> posts.create(PostChanges.builder().title("Mine").build()));
        assertThatThrownBy(() -> as(contributor, () -> posts.publish(draft.id())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability publish_post on site " + site.value());
        assertThat(Jdbc.strings(
                        dataSource,
                        "select status from qp_post where id = ?",
                        draft.id().value()))
                .containsExactly("draft");
    }

    @Test
    void anUpdateAnnouncesItselfAndPublishingDoesNot() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("one").status("publish"));
        recorder.seen().clear();
        as(
                editor,
                () -> posts.update(post.id(), PostChanges.builder().title("two").build()));
        assertThat(recorder.seen())
                .containsExactly("status publish>publish", "published", "updated one>two", "saved created=false");
        PostSnapshot draft = create(PostChanges.builder().title("draft"));
        recorder.seen().clear();
        as(editor, () -> posts.publish(draft.id()));
        assertThat(recorder.seen()).containsExactly("status draft>publish", "published", "saved created=false");
    }

    @Test
    void anUpdateAnnouncesTheStoredStatusAsItsStartingPoint() throws Exception {
        PostSnapshot draft = create(PostChanges.builder().title("draft"));
        recorder.seen().clear();
        as(
                editor,
                () -> posts.update(
                        draft.id(), PostChanges.builder().status("publish").build()));
        assertThat(recorder.seen())
                .containsExactly("status draft>publish", "published", "updated draft>draft", "saved created=false");
        Instant due = Instant.parse("2026-01-15T12:00:00Z").plus(Duration.ofDays(2));
        recorder.seen().clear();
        as(
                editor,
                () -> posts.update(
                        draft.id(), PostChanges.builder().dateGmt(due).build()));
        assertThat(recorder.seen())
                .containsExactly(
                        "status publish>future", "scheduled " + due, "updated draft>draft", "saved created=false");
    }

    @Test
    void publishingAPostOfAnotherSiteIsInvalid() {
        SiteId other = TestData.site(dataSource);
        PostId elsewhere = TestData.post(dataSource, other, "post", "draft", "", "Elsewhere");
        assertThatThrownBy(() -> as(editor, () -> posts.publish(elsewhere)))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"))
                .hasMessage("Invalid post ID.");
        assertThat(Jdbc.strings(dataSource, "select status from qp_post where id = ?", elsewhere.value()))
                .containsExactly("draft");
    }

    @Test
    void anAttachmentUpdateIsNotAnnouncedButPublishingOneIs() throws Exception {
        PostSnapshot file = create(PostChanges.builder().type("attachment").title("file"));
        recorder.seen().clear();
        as(
                editor,
                () -> posts.update(
                        file.id(), PostChanges.builder().title("renamed").build()));
        assertThat(recorder.seen()).isEmpty();
        assertThat(as(editor, () -> posts.publish(file.id()))).isTrue();
        assertThat(recorder.seen()).containsExactly("status inherit>publish", "published", "saved created=false");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select status from qp_post where id = ?",
                        file.id().value()))
                .containsExactly("publish");
    }

    @Test
    void anUnregisteredTypeIsPublishedWithoutBeingAnnouncedAsPublished() throws Exception {
        PostSnapshot thing = create(PostChanges.builder().type("thing").title("thing"));
        recorder.seen().clear();
        as(editor, () -> posts.publish(thing.id()));
        assertThat(recorder.seen()).containsExactly("status draft>publish", "saved created=false");
    }

    @Test
    void anUpdateRefusedAfterItsFirstWriteChangesNothingEvenWhenTheCallerCatchesTheRefusal() throws Exception {
        PostSnapshot post =
                create(PostChanges.builder().title("kept").content("body").status("publish"));
        AtomicInteger status = new AtomicInteger(-1);
        assertThatThrownBy(() -> as(editor, () -> {
                    assertThatThrownBy(() -> posts.update(
                                    post.id(),
                                    PostChanges.builder()
                                            .title("refuse")
                                            .slug("moved")
                                            .build()))
                            .isInstanceOfSatisfying(
                                    PostException.class,
                                    e -> assertThat(e.code()).isEqualTo("observer_refused"))
                            .hasMessage("the observer refused the update");
                    status.set(transactions.getStatus());
                    return null;
                }))
                .isInstanceOf(QuarkusTransactionException.class)
                .hasCauseInstanceOf(RollbackException.class);
        assertThat(status.get()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
        assertThat(Jdbc.strings(
                        dataSource,
                        "select title from qp_post where id = ?",
                        post.id().value()))
                .containsExactly("kept");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select slug from qp_post where id = ?",
                        post.id().value()))
                .containsExactly("kept");
        assertThat(as(editor, () -> posts.find(post.id())).orElseThrow().meta("_wp_old_slug"))
                .isEmpty();
    }

    @Test
    void publishIfDuePublishesADueScheduledPostWithOnlyThePublishEvents() throws Exception {
        PostSnapshot post = scheduledAt("2026-01-15T13:00:00Z");
        clock.set(Instant.parse("2026-01-15T13:00:00Z"));
        recorder.seen().clear();
        assertThat(as(editor, () -> posts.publishIfDue(post.id()))).isTrue();
        PostSnapshot published = as(editor, () -> posts.find(post.id())).orElseThrow();
        assertThat(published.status()).isEqualTo("publish");
        assertThat(published.publishedAt()).contains(Instant.parse("2026-01-15T13:00:00Z"));
        assertThat(published.modifiedAt()).isEqualTo(post.modifiedAt());
        assertThat(published.slug()).isEqualTo(post.slug());
        assertThat(published.author()).isEqualTo(post.author());
        assertThat(recorder.seen()).containsExactly("status future>publish", "published", "saved created=false");
        assertThat(as(editor, () -> posts.publishIfDue(post.id()))).isFalse();
    }

    @Test
    void publishIfDueLeavesAPostDatedLaterThanNowEvenByASecond() throws Exception {
        PostSnapshot post = scheduledAt("2026-01-15T13:00:00Z");
        clock.set(Instant.parse("2026-01-15T12:59:59Z"));
        recorder.seen().clear();
        assertThat(as(editor, () -> posts.publishIfDue(post.id()))).isFalse();
        assertThat(as(editor, () -> posts.find(post.id())).orElseThrow().status())
                .isEqualTo("future");
        assertThat(recorder.seen()).isEmpty();
        clock.set(Instant.parse("2026-01-15T13:00:00Z"));
        assertThat(as(editor, () -> posts.publishIfDue(post.id()))).isTrue();
    }

    @Test
    void publishIfDueDoesNothingForAPostThatIsNotScheduled() throws Exception {
        PostSnapshot draft = create(PostChanges.builder().title("draft").status("draft"));
        PostSnapshot live = create(PostChanges.builder().title("live").status("publish"));
        PostSnapshot binned = scheduledAt("2026-01-15T13:00:00Z");
        as(editor, () -> posts.trash(binned.id()));
        clock.set(Instant.parse("2026-01-15T14:00:00Z"));
        recorder.seen().clear();
        assertThat(as(editor, () -> posts.publishIfDue(draft.id()))).isFalse();
        assertThat(as(editor, () -> posts.publishIfDue(live.id()))).isFalse();
        assertThat(as(editor, () -> posts.publishIfDue(binned.id()))).isFalse();
        assertThat(as(editor, () -> posts.find(draft.id())).orElseThrow().status())
                .isEqualTo("draft");
        assertThat(as(editor, () -> posts.find(binned.id())).orElseThrow().status())
                .isEqualTo("trash");
        assertThat(recorder.seen()).isEmpty();
    }

    @Test
    void publishIfDueNeedsPublishPost() throws Exception {
        PostSnapshot post = scheduledAt("2026-01-15T13:00:00Z");
        clock.set(Instant.parse("2026-01-15T13:00:00Z"));
        UserId contributor = TestData.memberOfMain(dataSource, "contributor");
        assertThatThrownBy(() -> as(contributor, () -> posts.publishIfDue(post.id())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability publish_post on site " + site.value());
        assertThat(as(editor, () -> posts.find(post.id())).orElseThrow().status())
                .isEqualTo("future");
        assertThat(as(Identities.system(), () -> posts.publishIfDue(post.id()))).isTrue();
    }

    @Test
    void publishIfDueOnAMissingPostIsInvalid() {
        assertThatThrownBy(() -> as(editor, () -> posts.publishIfDue(PostId.of(Long.MAX_VALUE))))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"))
                .hasMessage("Invalid post ID.");
    }
}
