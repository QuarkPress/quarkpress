package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.StickyChanged;

class StickyDbTest {

    @ApplicationScoped
    static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();
        final AtomicBoolean refuse = new AtomicBoolean();

        /** Read through methods: the injected client proxy does not share the bean's fields. */
        List<String> seen() {
            return seen;
        }

        void refuse(boolean value) {
            refuse.set(value);
        }

        void sticky(@Observes StickyChanged event) {
            seen.add(event.id().value() + "=" + event.sticky());
            if (refuse.get()) {
                throw new IllegalStateException("the observer refused the change");
            }
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(StickyDbTest.class, Recorder.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    Recorder recorder;

    @Inject
    TransactionManager transactions;

    SiteId site;
    UserId editor;

    @BeforeEach
    void setUp() {
        site = TestData.site(dataSource);
        editor = TestData.memberOfMain(dataSource, "editor");
        recorder.seen().clear();
        recorder.refuse(false);
    }

    private <T> T as(UserId user, Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.user(user), work);
    }

    private PostSnapshot create(PostChanges.Builder changes) throws Exception {
        return as(editor, () -> posts.create(changes.build()));
    }

    private boolean stuck(PostSnapshot post) {
        return Jdbc.count(
                        dataSource,
                        "select count(*) from qp_post where id = ? and sticky = true",
                        post.id().value())
                == 1;
    }

    @Test
    void stickingAndUnstickingChangeThePostOnce() throws Exception {
        PostSnapshot first = create(PostChanges.builder().title("First").status("publish"));
        PostSnapshot second = create(PostChanges.builder().title("Second").status("draft"));
        assertThat(as(editor, () -> posts.stick(second.id()))).isTrue();
        assertThat(as(editor, () -> posts.stick(first.id()))).isTrue();
        assertThat(as(editor, () -> posts.stick(first.id()))).isFalse();
        assertThat(as(editor, () -> posts.stickyPosts())).containsExactly(first.id(), second.id());
        assertThat(as(editor, () -> posts.unstick(second.id()))).isTrue();
        assertThat(as(editor, () -> posts.unstick(second.id()))).isFalse();
        assertThat(as(editor, () -> posts.find(first.id())).orElseThrow().sticky())
                .isTrue();
        assertThat(recorder.seen())
                .containsExactly(
                        second.id().value() + "=true",
                        first.id().value() + "=true",
                        second.id().value() + "=false");
    }

    @Test
    void stickyPostsOfOtherSitesAreNotListed() throws Exception {
        PostSnapshot mine = create(PostChanges.builder().title("Mine").status("publish"));
        as(editor, () -> posts.stick(mine.id()));
        SiteId other = TestData.site(dataSource);
        TestData.member(dataSource, other, editor, "[\"editor\"]");
        List<PostId> there = Identities.onSite(other, Identities.user(editor), () -> {
            PostSnapshot theirs = posts.create(
                    PostChanges.builder().title("Theirs").status("publish").build());
            assertThat(posts.stickyPosts()).isEmpty();
            posts.stick(theirs.id());
            assertThatThrownBy(() -> posts.stick(mine.id()))
                    .isInstanceOfSatisfying(
                            PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"))
                    .hasMessage("Invalid post ID.");
            return posts.stickyPosts();
        });
        assertThat(there).hasSize(1).doesNotContain(mine.id());
        assertThat(as(editor, () -> posts.stickyPosts())).containsExactly(mine.id());
    }

    @Test
    void anAnonymousCallerSeesTheStickyList() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Public").status("publish"));
        as(editor, () -> posts.stick(post.id()));
        assertThat(Identities.onSite(site, Identities.anonymous(), () -> posts.stickyPosts()))
                .containsExactly(post.id());
    }

    @Test
    void stickingNeedsEditPostThenEditOthersPostsAndPublishPosts() throws Exception {
        UserId author = TestData.memberOfMain(dataSource, "author");
        UserId contributor = TestData.memberOfMain(dataSource, "contributor");
        PostSnapshot own = as(
                author,
                () -> posts.create(
                        PostChanges.builder().title("Own").status("publish").build()));
        assertThatThrownBy(() -> as(author, () -> posts.stick(own.id())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_others_posts"));
        assertThatThrownBy(() -> as(contributor, () -> posts.unstick(own.id())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_post"));
        assertThatThrownBy(() -> Identities.onSite(site, Identities.anonymous(), () -> posts.stick(own.id())))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(lacks("edit_post"));
        assertThat(stuck(own)).isFalse();
    }

    @Test
    void stickingAnotherUsersPrivatePostNeedsEditPostOnIt() throws Exception {
        UserId othersEditor = TestData.profile(dataSource, "others-editor");
        Jdbc.update(
                dataSource,
                "insert into qp_site_membership (site_id, user_id, roles, capabilities) values (?, ?, " + TestDb.json()
                        + ", " + TestDb.json() + ")",
                TestData.MAIN.value(),
                othersEditor.value(),
                "[\"author\"]",
                "{\"edit_others_posts\": true}");
        PostSnapshot hidden = create(PostChanges.builder().title("Hidden").status("private"));
        PostSnapshot published = create(PostChanges.builder().title("Published").status("publish"));
        assertThatThrownBy(() -> as(othersEditor, () -> posts.stick(hidden.id())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_post"));
        assertThat(stuck(hidden)).isFalse();
        assertThat(as(othersEditor, () -> posts.stick(published.id()))).isTrue();
        as(editor, () -> posts.stick(hidden.id()));
        assertThatThrownBy(() -> as(othersEditor, () -> posts.unstick(hidden.id())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_post"));
        assertThat(stuck(hidden)).isTrue();
    }

    private String lacks(String capability) {
        return "the caller lacks the capability " + capability + " on site " + site.value();
    }

    @Test
    void aRefusedCallerKeepsItsTransactionUsable() throws Exception {
        UserId author = TestData.memberOfMain(dataSource, "author");
        PostSnapshot post = create(PostChanges.builder().title("Plain").status("publish"));
        as(editor, () -> posts.stick(post.id()));
        String lacks = lacks("edit_post");
        Identities.onSite(site, Identities.user(author), () -> {
            assertThatThrownBy(() -> posts.stick(post.id()))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage(lacks);
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            assertThatThrownBy(() -> posts.unstick(post.id()))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage(lacks);
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            return null;
        });
        PostSnapshot other = create(PostChanges.builder().title("Other").status("publish"));
        as(editor, () -> {
            assertThat(posts.unstick(post.id())).isTrue();
            return null;
        });
        Identities.onSite(site, Identities.user(author), () -> {
            assertThatThrownBy(() -> posts.stick(other.id())).isInstanceOf(ForbiddenException.class);
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            Identities.onSite(site, Identities.user(editor), () -> posts.stick(other.id()));
            return null;
        });
        assertThat(stuck(other)).isTrue();
        assertThat(stuck(post)).isFalse();
    }

    @Test
    void aFailureAfterTheWriteRollsBackTheStickiness() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Kept").status("publish"));
        recorder.refuse(true);
        AtomicInteger status = new AtomicInteger(-1);
        assertThatThrownBy(() -> as(editor, () -> {
                    assertThatThrownBy(() -> posts.stick(post.id()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("the observer refused the change");
                    status.set(transactions.getStatus());
                    return null;
                }))
                .isInstanceOf(QuarkusTransactionException.class)
                .hasCauseInstanceOf(RollbackException.class);
        assertThat(status.get()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
        assertThat(stuck(post)).isFalse();
    }

    @Test
    void aMissingPostIsInvalid() throws Exception {
        PostId missing = PostId.of(999_999_999L);
        for (Callable<Boolean> call :
                List.<Callable<Boolean>>of(() -> posts.stick(missing), () -> posts.unstick(missing))) {
            assertThatThrownBy(() -> as(editor, call))
                    .isInstanceOfSatisfying(
                            PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"))
                    .hasMessage("Invalid post ID.");
        }
    }

    @Test
    void onlyPostsWithoutPasswordCanBeSticky() throws Exception {
        PostSnapshot page =
                create(PostChanges.builder().title("Page").type("page").status("publish"));
        assertThatThrownBy(() -> as(editor, () -> posts.stick(page.id())))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("rest_invalid_field"))
                .hasMessage("only posts of type post can be sticky; post "
                        + page.id().value() + " is a page");
        PostSnapshot locked =
                create(PostChanges.builder().title("Locked").status("publish").password("pw"));
        assertThatThrownBy(() -> as(editor, () -> posts.stick(locked.id())))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("rest_invalid_field"))
                .hasMessage("A password protected post can not be set to sticky.");
        PostSnapshot sticky = create(PostChanges.builder().title("Sticky").status("publish"));
        as(editor, () -> posts.stick(sticky.id()));
        assertThatThrownBy(() -> as(
                        editor,
                        () -> posts.update(
                                sticky.id(),
                                PostChanges.builder().password("pw").build())))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("rest_invalid_field"))
                .hasMessage("A sticky post can not be password protected.");
        assertThat(as(
                                editor,
                                () -> posts.update(
                                        sticky.id(),
                                        PostChanges.builder().password("").build()))
                        .sticky())
                .isTrue();
        assertThat(recorder.seen()).containsExactly(sticky.id().value() + "=true");
    }

    @Test
    void stickingLeavesTheVersionAndTheModificationDateAlone() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Steady").status("publish"));
        List<String> before = Jdbc.strings(
                dataSource,
                "select concat(version, '|', modified_at) from qp_post where id = ?",
                post.id().value());
        as(editor, () -> posts.stick(post.id()));
        as(editor, () -> posts.unstick(post.id()));
        as(editor, () -> posts.stick(post.id()));
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(version, '|', modified_at) from qp_post where id = ?",
                        post.id().value()))
                .isEqualTo(before);
        PostSnapshot now = as(editor, () -> posts.find(post.id())).orElseThrow();
        assertThat(now.version()).isEqualTo(post.version());
        assertThat(now.modifiedAt()).isEqualTo(post.modifiedAt());
        PostSnapshot updated = as(
                editor,
                () -> posts.update(
                        post.id(),
                        PostChanges.builder()
                                .title("Renamed")
                                .expectedVersion(post.version())
                                .build()));
        assertThat(updated.title()).isEqualTo("Renamed");
        assertThat(updated.sticky()).isTrue();
        assertThat(stuck(post)).isTrue();
    }

    @Test
    void refusalsForTypeAndPasswordLeaveTheTransactionActive() throws Exception {
        PostSnapshot page =
                create(PostChanges.builder().title("Page").type("page").status("publish"));
        PostSnapshot locked =
                create(PostChanges.builder().title("Locked").status("publish").password("pw"));
        PostSnapshot plain = create(PostChanges.builder().title("Plain").status("publish"));
        as(editor, () -> {
            assertThatThrownBy(() -> posts.stick(page.id())).isInstanceOf(PostException.class);
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            assertThatThrownBy(() -> posts.stick(locked.id())).isInstanceOf(PostException.class);
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            assertThat(posts.stick(plain.id())).isTrue();
            return null;
        });
        assertThat(stuck(plain)).isTrue();
    }

    @Test
    void aTypeChangeKeepsTheStickiness() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Moving").status("publish"));
        as(editor, () -> posts.stick(post.id()));
        PostSnapshot page = as(
                editor,
                () -> posts.update(post.id(), PostChanges.builder().type("page").build()));
        assertThat(page.sticky()).isTrue();
        assertThat(as(editor, () -> posts.stickyPosts())).containsExactly(post.id());
    }
}
