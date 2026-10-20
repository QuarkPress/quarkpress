package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostDraft;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostUpdated;
import org.quarkpress.posts.PostWriteContext;
import org.quarkpress.posts.PostWriteFilter;
import org.quarkpress.posts.Posts;

class HierarchyDbTest {

    @ApplicationScoped
    static class Recorder {
        final List<Long> updated = new CopyOnWriteArrayList<>();

        /** Read through a method: the injected client proxy does not share the bean's fields. */
        List<Long> updated() {
            return updated;
        }

        void updated(@Observes PostUpdated event) {
            updated.add(event.after().id().value());
        }
    }

    /** Refuses the write of a post titled "Refused" after everything before the write filters has run. */
    @ApplicationScoped
    static class Refusing implements PostWriteFilter {
        @Override
        public PostDraft apply(PostDraft draft, PostWriteContext context) {
            if (draft.title().equals("Refused")) {
                throw new PostException("refused_by_test", "Refused by the test filter.");
            }
            return draft;
        }
    }

    /** Thrown by a test to leave a transaction it has inspected. */
    static class Leave extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Leave() {
            super("leave");
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(HierarchyDbTest.class, Recorder.class, Refusing.class);

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

    @BeforeEach
    void setUp() {
        site = TestData.site(dataSource);
        recorder.updated().clear();
    }

    private PostSnapshot page(String title, String status, PostId parent) throws Exception {
        return post("page", title, status, parent);
    }

    private PostSnapshot post(String type, String title, String status, PostId parent) throws Exception {
        PostChanges.Builder changes =
                PostChanges.builder().type(type).title(title).status(status);
        if (parent != null) {
            changes.parent(parent);
        }
        return Identities.onSite(site, Identities.system(), () -> posts.create(changes.build()));
    }

    private PostSnapshot setParent(PostId post, long parent) throws Exception {
        return Identities.onSite(
                site,
                Identities.system(),
                () -> posts.update(post, PostChanges.builder().parent(parent).build()));
    }

    private List<String> parentOf(PostId post) {
        return Jdbc.strings(dataSource, "select parent_id from qp_post where id = ?", post.value());
    }

    private void storeParent(PostId post, PostId parent) {
        Jdbc.update(dataSource, "update qp_post set parent_id = ? where id = ?", parent.value(), post.value());
    }

    @Test
    void aParentClosingALoopLeavesThePostTopLevel() throws Exception {
        PostSnapshot a = page("A", "publish", null);
        PostSnapshot b = page("B", "publish", a.id());
        PostSnapshot c = page("C", "publish", b.id());
        assertThat(setParent(a.id(), c.id().value()).parent()).isEmpty();
        assertThat(setParent(b.id(), b.id().value()).parent()).isEmpty();
    }

    @Test
    void aLoopMadeOutsideTheServiceIsCutWhenAPostJoinsIt() throws Exception {
        PostSnapshot d = page("D", "publish", null);
        PostSnapshot e = page("E", "publish", null);
        PostSnapshot f = page("F", "publish", null);
        Jdbc.update(dataSource, "update qp_post set parent_id = ? where id = ?", e.id().value(), d.id().value());
        Jdbc.update(dataSource, "update qp_post set parent_id = ? where id = ?", d.id().value(), e.id().value());
        recorder.updated().clear();
        assertThat(setParent(f.id(), d.id().value()).parent()).contains(d.id());
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_post where id in (?, ?) and parent_id is null",
                        d.id().value(),
                        e.id().value()))
                .isEqualTo(2);
        assertThat(recorder.updated()).containsExactly(d.id().value(), e.id().value(), f.id().value());
    }

    @Test
    void aPostLosesItsOldParentWhenTheNewOneWouldCloseALoop() throws Exception {
        PostSnapshot a = page("A", "publish", null);
        PostSnapshot b = page("B", "publish", a.id());
        PostSnapshot c = page("C", "publish", b.id());
        recorder.updated().clear();
        assertThat(setParent(b.id(), c.id().value()).parent()).isEmpty();
        assertThat(parentOf(b.id())).containsExactly((String) null);
        assertThat(parentOf(c.id())).containsExactly(Long.toString(b.id().value()));
        assertThat(parentOf(a.id())).containsExactly((String) null);
        assertThat(recorder.updated()).containsExactly(b.id().value());
    }

    @Test
    void aPostStoredInALoopOrAsItsOwnParentBecomesTopLevelOnAnyEdit() throws Exception {
        PostSnapshot x = page("X", "publish", null);
        PostSnapshot y = page("Y", "publish", null);
        PostSnapshot z = page("Z", "publish", null);
        storeParent(x.id(), y.id());
        storeParent(y.id(), x.id());
        storeParent(z.id(), z.id());
        recorder.updated().clear();
        PostSnapshot edited = Identities.onSite(
                site,
                Identities.system(),
                () -> posts.update(x.id(), PostChanges.builder().title("X2").build()));
        assertThat(edited.parent()).isEmpty();
        assertThat(parentOf(x.id())).containsExactly((String) null);
        assertThat(parentOf(y.id())).containsExactly(Long.toString(x.id().value()));
        assertThat(recorder.updated()).containsExactly(x.id().value());
        Identities.onSite(
                site,
                Identities.system(),
                () -> posts.update(z.id(), PostChanges.builder().title("Z2").build()));
        assertThat(parentOf(z.id())).containsExactly((String) null);
    }

    @Test
    void cutMembersMeetingAtTheTopLevelAreSuffixedInWordPressOrder() throws Exception {
        PostSnapshot left = page("Left", "publish", null);
        PostSnapshot right = page("Right", "publish", null);
        PostSnapshot first = page("dup", "publish", left.id());
        PostSnapshot second = page("dup", "publish", right.id());
        assertThat(first.slug()).isEqualTo("dup");
        assertThat(second.slug()).isEqualTo("dup");
        PostSnapshot joining = page("Joining", "publish", null);
        storeParent(first.id(), second.id());
        storeParent(second.id(), first.id());
        recorder.updated().clear();
        assertThat(setParent(joining.id(), first.id().value()).parent()).contains(first.id());
        assertThat(recorder.updated())
                .containsExactly(
                        first.id().value(), second.id().value(), joining.id().value());
        assertThat(Jdbc.strings(
                        dataSource,
                        "select slug from qp_post where id = ?",
                        second.id().value()))
                .containsExactly("dup-2");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select slug from qp_post where id = ?",
                        first.id().value()))
                .containsExactly("dup");
    }

    @Test
    void anInvalidDateIsRefusedBeforeAnyLoopMemberIsCut() throws Exception {
        PostSnapshot d = page("D", "publish", null);
        PostSnapshot e = page("E", "publish", null);
        PostSnapshot f = page("F", "publish", null);
        storeParent(d.id(), e.id());
        storeParent(e.id(), d.id());
        recorder.updated().clear();
        int status = Identities.onSite(site, Identities.system(), () -> {
            assertThatThrownBy(() -> posts.update(
                            f.id(),
                            PostChanges.builder()
                                    .parent(d.id())
                                    .date(LocalDateTime.of(999, 1, 1, 0, 0))
                                    .build()))
                    .isInstanceOf(PostException.class)
                    .hasMessage("Invalid date.")
                    .extracting(failure -> ((PostException) failure).code())
                    .isEqualTo("invalid_date");
            return transactions.getStatus();
        });
        assertThat(status).isEqualTo(Status.STATUS_ACTIVE);
        assertThat(recorder.updated()).isEmpty();
        assertThat(parentOf(d.id())).containsExactly(Long.toString(e.id().value()));
        assertThat(parentOf(e.id())).containsExactly(Long.toString(d.id().value()));
        assertThat(parentOf(f.id())).containsExactly((String) null);
    }

    @Test
    void loopsAreBrokenForTypesWithoutHierarchyToo() throws Exception {
        PostSnapshot a = post("post", "Flat A", "publish", null);
        PostSnapshot b = post("post", "Flat B", "publish", a.id());
        assertThat(b.parent()).contains(a.id());
        assertThat(setParent(a.id(), b.id().value()).parent()).isEmpty();
        PostSnapshot x = post("post", "Flat X", "publish", null);
        PostSnapshot y = post("post", "Flat Y", "publish", null);
        PostSnapshot z = post("post", "Flat Z", "publish", null);
        storeParent(x.id(), y.id());
        storeParent(y.id(), x.id());
        assertThat(setParent(z.id(), x.id().value()).parent()).contains(x.id());
        assertThat(parentOf(x.id())).containsExactly((String) null);
        assertThat(parentOf(y.id())).containsExactly((String) null);
    }

    @Test
    void aParentOfAnotherSiteOrAMissingIdIsRefusedAndNothingIsWritten() throws Exception {
        PostSnapshot own = page("Own", "publish", null);
        SiteId otherSite = TestData.site(dataSource);
        PostSnapshot foreign = Identities.onSite(
                otherSite,
                Identities.system(),
                () -> posts.create(PostChanges.builder()
                        .type("page")
                        .title("Foreign")
                        .status("publish")
                        .build()));
        long missing = foreign.id().value() + 1_000_000;
        for (long parent : new long[] {foreign.id().value(), missing}) {
            assertThatThrownBy(() -> setParent(own.id(), parent))
                    .isInstanceOf(PostException.class)
                    .hasMessage("Invalid post parent ID: " + parent)
                    .extracting(failure -> ((PostException) failure).code())
                    .isEqualTo("invalid_parent");
            assertThatThrownBy(() -> page("Child", "publish", PostId.of(parent)))
                    .isInstanceOf(PostException.class)
                    .hasMessage("Invalid post parent ID: " + parent)
                    .extracting(failure -> ((PostException) failure).code())
                    .isEqualTo("invalid_parent");
        }
        assertThat(parentOf(own.id())).containsExactly((String) null);
        assertThat(Jdbc.count(
                        dataSource,
                        "select version from qp_post where id = ?",
                        own.id().value()))
                .isEqualTo(own.version());
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post where site_id = ?", site.value()))
                .isEqualTo(1);
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post where site_id = ?", otherSite.value()))
                .isEqualTo(1);
    }

    @Test
    void aFailureAfterTheLoopIsCutRollsTheCutBack() throws Exception {
        PostSnapshot d = page("D", "publish", null);
        PostSnapshot e = page("E", "publish", null);
        PostSnapshot f = page("F", "publish", null);
        storeParent(d.id(), e.id());
        storeParent(e.id(), d.id());
        assertThatThrownBy(() -> Identities.onSite(site, Identities.system(), () -> {
                    assertThatThrownBy(() -> posts.update(
                                    f.id(),
                                    PostChanges.builder()
                                            .parent(d.id())
                                            .title("Refused")
                                            .build()))
                            .isInstanceOf(PostException.class)
                            .hasMessage("Refused by the test filter.");
                    assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
                    throw new Leave();
                }))
                .isInstanceOf(Leave.class);
        assertThat(parentOf(d.id())).containsExactly(Long.toString(e.id().value()));
        assertThat(parentOf(e.id())).containsExactly(Long.toString(d.id().value()));
        assertThat(parentOf(f.id())).containsExactly((String) null);
        assertThat(Jdbc.strings(dataSource, "select title from qp_post where id = ?", f.id().value()))
                .containsExactly("F");
    }

    @Test
    void uriJoinsTheAncestorsSlugs() throws Exception {
        PostSnapshot about = page("about", "publish", null);
        PostSnapshot team = page("team", "publish", about.id());
        PostSnapshot alice = page("alice", "publish", team.id());
        assertThat(Identities.onSite(site, Identities.system(), () -> posts.uri(alice.id())))
                .isEqualTo("about/team/alice");
        assertThat(Identities.onSite(site, Identities.system(), () -> posts.ancestors(alice.id())))
                .containsExactly(team.id(), about.id());
        Identities.onSite(
                site,
                Identities.system(),
                () -> posts.update(
                        team.id(),
                        PostChanges.builder().status("draft").slug("").build()));
        assertThat(Identities.onSite(site, Identities.system(), () -> posts.uri(alice.id())))
                .isEqualTo("about/alice");
        PostSnapshot japan = page(new String(new int[] {0x65E5, 0x672C}, 0, 2), "publish", about.id());
        assertThat(Identities.onSite(site, Identities.system(), () -> posts.uri(japan.id())))
                .isEqualTo("about/%e6%97%a5%e6%9c%ac");
        PostSnapshot child = page("child", "draft", about.id());
        assertThat(Identities.onSite(site, Identities.system(), () -> posts.uri(child.id())))
                .isEqualTo("about/");
        PostSnapshot zero = page("0", "publish", null);
        // WordPress suffixes a numeric page slug, so a slug "0" is only made by a direct edit
        Jdbc.update(
                dataSource,
                "update qp_post set slug = '0' where id = ?",
                zero.id().value());
        PostSnapshot kid = page("kid", "publish", zero.id());
        assertThat(Identities.onSite(site, Identities.system(), () -> posts.uri(kid.id())))
                .isEqualTo("kid");
    }

    @Test
    void ancestorsAndUriStopAtALoopMadeOutsideTheService() throws Exception {
        PostSnapshot top = page("top", "publish", null);
        PostSnapshot middle = page("middle", "publish", top.id());
        PostSnapshot leaf = page("leaf", "publish", middle.id());
        storeParent(top.id(), middle.id());
        assertThat(Identities.onSite(site, Identities.system(), () -> posts.ancestors(leaf.id())))
                .containsExactly(middle.id(), top.id());
        assertThat(Identities.onSite(site, Identities.system(), () -> posts.uri(leaf.id())))
                .isEqualTo("top/middle/leaf");
        assertThat(Identities.onSite(site, Identities.system(), () -> posts.ancestors(top.id())))
                .containsExactly(middle.id());
    }

    @Test
    void readingTheHierarchyFollowsTheReadRules() throws Exception {
        UserId subscriber = TestData.memberOfMain(dataSource, "subscriber");
        PostSnapshot about = page("about", "publish", null);
        PostSnapshot open = page("open", "publish", about.id());
        PostSnapshot hidden = page("hidden", "draft", about.id());
        assertThat(Identities.onSite(site, Identities.anonymous(), () -> posts.uri(open.id())))
                .isEqualTo("about/open");
        assertThat(Identities.onSite(site, Identities.anonymous(), () -> posts.ancestors(open.id())))
                .containsExactly(about.id());
        String lacks = "the caller lacks the capability read_post on site " + site.value();
        assertThatThrownBy(() -> Identities.onSite(site, Identities.anonymous(), () -> posts.uri(hidden.id())))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(lacks);
        assertThatThrownBy(() -> Identities.onSite(site, Identities.anonymous(), () -> posts.ancestors(hidden.id())))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(lacks);
        assertThatThrownBy(() -> Identities.onSite(site, Identities.user(subscriber), () -> posts.uri(hidden.id())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks);
        assertThatThrownBy(
                        () -> Identities.onSite(site, Identities.user(subscriber), () -> posts.ancestors(hidden.id())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks);
        SiteId otherSite = TestData.site(dataSource);
        for (PostId post : new PostId[] {open.id(), PostId.of(open.id().value() + 1_000_000)}) {
            assertThatThrownBy(() -> Identities.onSite(otherSite, Identities.system(), () -> posts.uri(post)))
                    .isInstanceOf(PostException.class)
                    .hasMessage("Invalid post ID.")
                    .extracting(failure -> ((PostException) failure).code())
                    .isEqualTo("invalid_post");
            assertThatThrownBy(() -> Identities.onSite(otherSite, Identities.system(), () -> posts.ancestors(post)))
                    .isInstanceOf(PostException.class)
                    .hasMessage("Invalid post ID.")
                    .extracting(failure -> ((PostException) failure).code())
                    .isEqualTo("invalid_post");
        }
    }
}
