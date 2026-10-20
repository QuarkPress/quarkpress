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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.OptionChanged;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostDeleted;
import org.quarkpress.posts.PostDeleting;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostTrashed;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.StickyChanged;

class DeleteDbTest {

    /**
     * Records the events of a delete; can refuse the delete after it has written, and can write to another post while
     * a post is being deleted, as a module cleaning up would.
     */
    @ApplicationScoped
    static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();
        final AtomicBoolean refuse = new AtomicBoolean();
        final AtomicReference<PostId> cleanUp = new AtomicReference<>();

        // the test reaches this bean through its client proxy: state goes through methods, not fields
        List<String> seen() {
            return seen;
        }

        void refuse(boolean value) {
            refuse.set(value);
        }

        void cleanUp(PostId post) {
            cleanUp.set(post);
        }

        @Inject
        Posts posts;

        void deleting(@Observes PostDeleting event) {
            seen.add("deleting " + event.post().title());
            PostId other = cleanUp.get();
            if (other != null) {
                posts.update(other, PostChanges.builder().title("Cleaned").build());
            }
        }

        void sticky(@Observes StickyChanged event) {
            seen.add("sticky " + event.sticky());
        }

        void option(@Observes OptionChanged event) {
            seen.add("option " + event.name());
        }

        void trashed(@Observes PostTrashed event) {
            seen.add("trashed " + event.post().title());
        }

        void deleted(@Observes PostDeleted event) {
            seen.add("deleted " + event.type());
            if (refuse.get()) {
                throw new IllegalStateException("the observer refused the delete");
            }
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(DeleteDbTest.class, Recorder.class);

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
        recorder.cleanUp(null);
    }

    private <T> T as(Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.user(editor), work);
    }

    private PostSnapshot create(PostChanges.Builder changes) throws Exception {
        return as(() -> posts.create(changes.build()));
    }

    private boolean exists(PostId post) {
        return Jdbc.count(dataSource, "select count(*) from qp_post where id = ?", post.value()) == 1;
    }

    private Long parentOf(PostId post) {
        List<String> parents = Jdbc.strings(dataSource, "select parent_id from qp_post where id = ?", post.value());
        return parents.get(0) == null ? null : Long.valueOf(parents.get(0));
    }

    private String versionAndModified(PostId post) {
        return Jdbc.strings(dataSource, "select version from qp_post where id = ?", post.value())
                        .get(0)
                + " "
                + Jdbc.strings(dataSource, "select modified_at from qp_post where id = ?", post.value())
                        .get(0);
    }

    private List<String> option(String name) {
        return Jdbc.strings(
                dataSource, "select value from qp_option where site_id = ? and name = ?", site.value(), name);
    }

    private String lacks(String capability) {
        return "the caller lacks the capability " + capability + " on site " + site.value();
    }

    @Test
    void postsAndPagesGoToTheTrashFirst() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Post").status("publish"));
        assertThat(as(() -> posts.delete(post.id(), false)))
                .map(PostSnapshot::status)
                .contains("publish");
        assertThat(as(() -> posts.find(post.id())).orElseThrow().status()).isEqualTo("trash");
        assertThat(recorder.seen()).containsExactly("trashed Post");
        assertThat(as(() -> posts.delete(post.id(), false)))
                .map(PostSnapshot::status)
                .contains("trash");
        assertThat(exists(post.id())).isFalse();
        PostSnapshot page =
                create(PostChanges.builder().type("page").title("Page").status("publish"));
        as(() -> posts.delete(page.id(), false));
        assertThat(as(() -> posts.find(page.id())).orElseThrow().status()).isEqualTo("trash");
    }

    @Test
    void forceDeletesAPostThatIsNotTrashed() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Post").status("publish"));
        PostSnapshot before = as(() -> posts.delete(post.id(), true)).orElseThrow();
        assertThat(before.id()).isEqualTo(post.id());
        assertThat(before.status()).isEqualTo("publish");
        assertThat(before.title()).isEqualTo("Post");
        assertThat(exists(post.id())).isFalse();
        assertThat(as(() -> posts.find(post.id()))).isEmpty();
    }

    @Test
    void otherTypesAreDeletedAtOnce() throws Exception {
        PostSnapshot block =
                create(PostChanges.builder().type("wp_block").title("Block").status("publish"));
        PostSnapshot file = create(PostChanges.builder().type("attachment").title("File"));
        as(() -> posts.delete(block.id(), false));
        as(() -> posts.delete(file.id(), false));
        assertThat(exists(block.id())).isFalse();
        assertThat(exists(file.id())).isFalse();
        assertThat(recorder.seen())
                .containsExactly("deleting Block", "deleted wp_block", "deleting File", "deleted attachment");
    }

    @Test
    void aDeleteAnnouncesItselfAndUnsticks() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Sticky").status("publish"));
        as(() -> posts.stick(post.id()));
        recorder.seen().clear();
        as(() -> posts.delete(post.id(), true));
        assertThat(recorder.seen()).containsExactly("deleting Sticky", "sticky false", "deleted post");
        assertThat(as(() -> posts.stickyPosts())).doesNotContain(post.id());
    }

    @Test
    void deletingTheFrontPageResetsTheReadingSettingsBeforeItIsAnnounced() throws Exception {
        PostSnapshot front =
                create(PostChanges.builder().type("page").title("Front").status("publish"));
        PostSnapshot blog =
                create(PostChanges.builder().type("page").title("Blog").status("publish"));
        TestData.siteOption(dataSource, site, "show_on_front", "\"page\"");
        TestData.siteOption(
                dataSource, site, "page_on_front", Long.toString(front.id().value()));
        TestData.siteOption(dataSource, site, "page_for_posts", "\"" + blog.id().value() + "abc\"");
        recorder.seen().clear();
        as(() -> posts.delete(front.id(), true));
        assertThat(recorder.seen())
                .containsExactly("deleting Front", "option show_on_front", "option page_on_front", "deleted page");
        assertThat(option("show_on_front")).containsExactly("\"posts\"");
        assertThat(option("page_on_front")).containsExactly("0");
        as(() -> posts.delete(blog.id(), true));
        assertThat(option("page_for_posts")).containsExactly("0");
    }

    @Test
    void childrenOfADeletedPageMoveUpAndAttachmentsFollow() throws Exception {
        PostSnapshot about =
                create(PostChanges.builder().type("page").title("About").status("publish"));
        PostSnapshot team = create(PostChanges.builder()
                .type("page")
                .title("Team")
                .status("publish")
                .parent(about.id()));
        PostSnapshot alice = create(PostChanges.builder()
                .type("page")
                .title("Alice")
                .status("publish")
                .parent(team.id()));
        PostSnapshot photo =
                create(PostChanges.builder().type("attachment").title("Photo").parent(team.id()));
        PostSnapshot block =
                create(PostChanges.builder().type("wp_block").title("Block").parent(team.id()));
        PostSnapshot post =
                create(PostChanges.builder().title("Post").status("publish").parent(team.id()));
        List<PostId> children = List.of(alice.id(), photo.id(), block.id(), post.id());
        List<String> stamps = children.stream().map(this::versionAndModified).toList();
        as(() -> posts.delete(team.id(), true));
        assertThat(parentOf(alice.id())).isEqualTo(about.id().value());
        assertThat(parentOf(photo.id())).isEqualTo(about.id().value());
        assertThat(parentOf(block.id())).isNull();
        assertThat(parentOf(post.id())).isNull();
        // WordPress changes only the parent of the children: no modification date, no version
        assertThat(children.stream().map(this::versionAndModified).toList()).isEqualTo(stamps);
        as(() -> posts.delete(about.id(), true));
        assertThat(parentOf(alice.id())).isNull();
        assertThat(parentOf(photo.id())).isNull();
        assertThat(exists(alice.id())).isTrue();
        assertThat(exists(photo.id())).isTrue();
    }

    @Test
    void anUpdateWithTheVersionReadBeforeTheParentWasDeletedSucceeds() throws Exception {
        PostSnapshot top =
                create(PostChanges.builder().type("page").title("Top").status("publish"));
        PostSnapshot middle = create(PostChanges.builder()
                .type("page")
                .title("Middle")
                .status("publish")
                .parent(top.id()));
        PostSnapshot leaf = create(PostChanges.builder()
                .type("page")
                .title("Leaf")
                .status("publish")
                .parent(middle.id()));
        PostSnapshot cleared =
                create(PostChanges.builder().type("wp_block").title("Block").parent(middle.id()));
        as(() -> posts.delete(middle.id(), true));
        PostSnapshot saved = as(() -> posts.update(
                leaf.id(),
                PostChanges.builder()
                        .title("Leaf again")
                        .expectedVersion(leaf.version())
                        .build()));
        assertThat(saved.title()).isEqualTo("Leaf again");
        assertThat(saved.parent()).contains(top.id());
        PostSnapshot block = as(() -> posts.update(
                cleared.id(),
                PostChanges.builder()
                        .title("Block again")
                        .expectedVersion(cleared.version())
                        .build()));
        assertThat(block.title()).isEqualTo("Block again");
        assertThat(block.parent()).isEmpty();
    }

    @Test
    void attachmentsOfADeletedPostMoveToItsParent() throws Exception {
        PostSnapshot top = create(PostChanges.builder().title("Top").status("publish"));
        PostSnapshot post =
                create(PostChanges.builder().title("Post").status("publish").parent(top.id()));
        PostSnapshot file =
                create(PostChanges.builder().type("attachment").title("File").parent(post.id()));
        PostSnapshot child =
                create(PostChanges.builder().title("Child").status("publish").parent(post.id()));
        as(() -> posts.delete(post.id(), true));
        assertThat(parentOf(file.id())).isEqualTo(top.id().value());
        assertThat(parentOf(child.id())).isNull();
        assertThat(exists(file.id())).isTrue();
        assertThat(exists(child.id())).isTrue();
    }

    @Test
    void childrenOfADeletedAttachmentLoseTheirParent() throws Exception {
        PostSnapshot page =
                create(PostChanges.builder().type("page").title("Page").status("publish"));
        PostSnapshot file =
                create(PostChanges.builder().type("attachment").title("File").parent(page.id()));
        PostSnapshot thumb =
                create(PostChanges.builder().type("attachment").title("Thumb").parent(file.id()));
        as(() -> posts.delete(file.id(), true));
        assertThat(parentOf(thumb.id())).isNull();
        assertThat(exists(thumb.id())).isTrue();
    }

    @Test
    void childrenOfAPostThatIsItsOwnParentLoseTheirParent() throws Exception {
        PostId loop = TestData.post(dataSource, site, "page", "publish", "loop", "Loop");
        PostId child = TestData.post(dataSource, site, "page", "publish", "child", "Child");
        Jdbc.update(dataSource, "update qp_post set parent_id = ? where id = ?", loop.value(), loop.value());
        Jdbc.update(dataSource, "update qp_post set parent_id = ? where id = ?", loop.value(), child.value());
        as(() -> posts.delete(loop, true));
        assertThat(exists(loop)).isFalse();
        assertThat(exists(child)).isTrue();
        assertThat(parentOf(child)).isNull();
    }

    @Test
    void deletingThePrivacyPageClearsItsSetting() throws Exception {
        PostSnapshot privacy =
                create(PostChanges.builder().type("page").title("Privacy").status("publish"));
        TestData.siteOption(
                dataSource,
                site,
                "wp_page_for_privacy_policy",
                "\" " + privacy.id().value() + "\"");
        recorder.seen().clear();
        as(() -> posts.delete(privacy.id(), true));
        assertThat(option("wp_page_for_privacy_policy")).containsExactly("0");
        assertThat(recorder.seen())
                .containsExactly("deleting Privacy", "option wp_page_for_privacy_policy", "deleted page");
    }

    @Test
    void aStickyPageIsUnstuckAfterTheReadingSettingsAndBeforeThePrivacyPage() throws Exception {
        PostSnapshot page =
                create(PostChanges.builder().type("page").title("Everything").status("publish"));
        String value = Long.toString(page.id().value());
        Jdbc.update(
                dataSource,
                "update qp_post set sticky = ? where id = ?",
                true,
                page.id().value());
        TestData.siteOption(dataSource, site, "show_on_front", "\"page\"");
        TestData.siteOption(dataSource, site, "page_on_front", value);
        TestData.siteOption(dataSource, site, "page_for_posts", value);
        TestData.siteOption(dataSource, site, "wp_page_for_privacy_policy", value);
        recorder.seen().clear();
        as(() -> posts.delete(page.id(), true));
        assertThat(recorder.seen())
                .containsExactly(
                        "deleting Everything",
                        "option show_on_front",
                        "option page_on_front",
                        "option page_for_posts",
                        "sticky false",
                        "option wp_page_for_privacy_policy",
                        "deleted page");
    }

    @Test
    void deletingAPostOfAnotherTypeLeavesThePrivacySetting() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Policy").status("publish"));
        String value = Long.toString(post.id().value());
        TestData.siteOption(dataSource, site, "wp_page_for_privacy_policy", value);
        TestData.siteOption(dataSource, site, "show_on_front", "\"page\"");
        TestData.siteOption(dataSource, site, "page_on_front", value);
        as(() -> posts.delete(post.id(), true));
        assertThat(option("wp_page_for_privacy_policy")).containsExactly(value);
        assertThat(option("page_on_front")).containsExactly(value);
        assertThat(option("show_on_front")).containsExactly("\"page\"");
    }

    @Test
    void observersOfTheDeleteMayWriteInItsTransaction() throws Exception {
        PostSnapshot other = create(PostChanges.builder().title("Other").status("publish"));
        PostSnapshot post = create(PostChanges.builder().title("Gone").status("publish"));
        recorder.cleanUp(other.id());
        as(() -> posts.delete(post.id(), true));
        assertThat(exists(post.id())).isFalse();
        assertThat(as(() -> posts.find(other.id())).orElseThrow().title()).isEqualTo("Cleaned");
    }

    @Test
    void aFailedDeleteChangesNothing() throws Exception {
        PostSnapshot top =
                create(PostChanges.builder().type("page").title("Top").status("publish"));
        PostSnapshot page = create(PostChanges.builder()
                .type("page")
                .title("Front")
                .status("publish")
                .parent(top.id()));
        PostSnapshot child = create(PostChanges.builder()
                .type("page")
                .title("Child")
                .status("publish")
                .parent(page.id()));
        PostSnapshot block =
                create(PostChanges.builder().type("wp_block").title("Block").parent(page.id()));
        PostSnapshot other = create(PostChanges.builder().title("Other").status("publish"));
        TestData.siteOption(dataSource, site, "show_on_front", "\"page\"");
        TestData.siteOption(
                dataSource, site, "page_on_front", Long.toString(page.id().value()));
        TestData.siteOption(
                dataSource,
                site,
                "wp_page_for_privacy_policy",
                Long.toString(page.id().value()));
        recorder.cleanUp(other.id());
        recorder.refuse(true);
        AtomicInteger status = new AtomicInteger(-1);
        assertThatThrownBy(() -> as(() -> {
                    assertThatThrownBy(() -> posts.delete(page.id(), true))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("the observer refused the delete");
                    status.set(transactions.getStatus());
                    return null;
                }))
                .isInstanceOf(QuarkusTransactionException.class)
                .hasCauseInstanceOf(RollbackException.class);
        assertThat(status.get()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
        recorder.refuse(false);
        recorder.cleanUp(null);
        assertThat(exists(page.id())).isTrue();
        assertThat(parentOf(child.id())).isEqualTo(page.id().value());
        assertThat(parentOf(block.id())).isEqualTo(page.id().value());
        assertThat(as(() -> posts.find(other.id())).orElseThrow().title()).isEqualTo("Other");
        assertThat(option("show_on_front")).containsExactly("\"page\"");
        assertThat(option("page_on_front"))
                .containsExactly(Long.toString(page.id().value()));
        assertThat(option("wp_page_for_privacy_policy"))
                .containsExactly(Long.toString(page.id().value()));
    }

    @Test
    void deletingNeedsDeletePost() throws Exception {
        UserId author = TestData.memberOfMain(dataSource, "author");
        UserId contributor = TestData.memberOfMain(dataSource, "contributor");
        PostSnapshot post = create(PostChanges.builder().title("Editors").status("publish"));
        for (boolean force : List.of(true, false)) {
            assertThatThrownBy(() ->
                            Identities.onSite(site, Identities.user(author), () -> posts.delete(post.id(), force)))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage(lacks("delete_post"));
            assertThatThrownBy(
                            () -> Identities.onSite(site, Identities.anonymous(), () -> posts.delete(post.id(), force)))
                    .isInstanceOf(UnauthorizedException.class)
                    .hasMessage(lacks("delete_post"));
        }
        assertThat(as(() -> posts.find(post.id())).orElseThrow().status()).isEqualTo("publish");
        PostSnapshot own = Identities.onSite(
                site,
                Identities.user(contributor),
                () -> posts.create(PostChanges.builder().title("Mine").build()));
        Identities.onSite(site, Identities.user(contributor), () -> posts.delete(own.id(), true));
        assertThat(exists(own.id())).isFalse();
    }

    @Test
    void aMissingPostIsInvalid() throws Exception {
        SiteId other = TestData.site(dataSource);
        PostId theirs = TestData.post(dataSource, other, "post", "publish", "theirs", "Theirs");
        for (PostId missing : List.of(PostId.of(Long.MAX_VALUE), theirs)) {
            for (boolean force : List.of(true, false)) {
                assertThatThrownBy(() -> as(() -> posts.delete(missing, force)))
                        .isInstanceOfSatisfying(
                                PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"))
                        .hasMessage("Invalid post ID.");
            }
        }
        assertThat(exists(theirs)).isTrue();
    }

    /**
     * Defence in depth: the composite parent key and ids unique across the network already keep the re-parenting of
     * one site from reaching a post of another; the site condition of the re-parenting queries is kept all the same.
     */
    @Test
    void reparentingTouchesOnlyTheDeletingSite() throws Exception {
        SiteId other = TestData.site(dataSource);
        PostId otherParent = TestData.post(dataSource, other, "page", "publish", "p", "P");
        PostId otherChild = TestData.post(dataSource, other, "page", "publish", "c", "C");
        Jdbc.update(
                dataSource, "update qp_post set parent_id = ? where id = ?", otherParent.value(), otherChild.value());
        PostSnapshot parent =
                create(PostChanges.builder().type("page").title("P").status("publish"));
        PostId otherBlock = TestData.post(dataSource, other, "wp_block", "publish", "b", "B");
        Jdbc.update(
                dataSource, "update qp_post set parent_id = ? where id = ?", otherParent.value(), otherBlock.value());
        create(PostChanges.builder().type("page").title("C").status("publish").parent(parent.id()));
        create(PostChanges.builder().type("wp_block").title("B").parent(parent.id()));
        as(() -> posts.delete(parent.id(), true));
        // both statements, the one handing children down and the one letting them go, kept to the deleting site
        for (PostId theirs : List.of(otherChild, otherBlock)) {
            assertThat(parentOf(theirs)).isEqualTo(otherParent.value());
            assertThat(Jdbc.strings(dataSource, "select version from qp_post where id = ?", theirs.value()))
                    .containsExactly("0");
        }
    }
}
