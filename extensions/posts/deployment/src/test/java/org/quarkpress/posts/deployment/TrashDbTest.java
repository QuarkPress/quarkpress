package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.OptionChanged;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostRestored;
import org.quarkpress.posts.PostSlugFilter;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatusChanged;
import org.quarkpress.posts.PostTrashed;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.SlugContext;
import org.quarkpress.posts.StickyChanged;

class TrashDbTest {

    /** Records the events of trashing and restoring; can refuse them, failing the call after it has written. */
    @ApplicationScoped
    static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();
        final AtomicBoolean refuse = new AtomicBoolean();
        final AtomicBoolean refuseSave = new AtomicBoolean();

        List<String> seen() {
            return seen;
        }

        void refuse(boolean value) {
            refuse.set(value);
        }

        void refuseSave(boolean value) {
            refuseSave.set(value);
        }

        void trashed(@Observes PostTrashed event) {
            seen.add("trashed " + event.post().id().value() + " " + event.post().status());
            if (refuse.get()) {
                throw new IllegalStateException("the observer refused the trash");
            }
        }

        void restored(@Observes PostRestored event) {
            seen.add("restored " + event.post().id().value() + " "
                    + event.post().status() + " " + event.previousStatus().orElse("-"));
            if (refuse.get()) {
                throw new IllegalStateException("the observer refused the restore");
            }
        }

        void sticky(@Observes StickyChanged event) {
            seen.add("sticky " + event.id().value() + " " + event.sticky());
        }

        void option(@Observes OptionChanged event) {
            seen.add("option " + event.name());
        }

        void status(@Observes PostStatusChanged event) {
            seen.add("status " + event.post().id().value() + " " + event.from() + ">" + event.to());
            if (refuseSave.get()) {
                throw new PostException("save_refused", "the observer refused the save");
            }
        }
    }

    /** Refuses slugs that name themselves refused, after any trashed post holding them was renamed. */
    @ApplicationScoped
    static class Refusal implements PostSlugFilter {
        @Override
        public String apply(String uniqueSlug, SlugContext context) {
            if (context.original().contains("refused")) {
                throw new PostException("slug_refused", "the slug " + context.original() + " is refused");
            }
            return uniqueSlug;
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app =
            PostsApp.with(TrashDbTest.class, TestClock.class, Recorder.class, Refusal.class);

    static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

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
        clock.set(NOW);
        site = TestData.siteIn(dataSource, "Europe/Paris");
        editor = TestData.memberOfMain(dataSource, "editor");
        recorder.seen().clear();
        recorder.refuse(false);
        recorder.refuseSave(false);
    }

    private <T> T as(Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.user(editor), work);
    }

    private PostSnapshot create(PostChanges.Builder changes) throws Exception {
        return as(() -> posts.create(changes.build()));
    }

    private PostSnapshot find(PostId post) throws Exception {
        return as(() -> posts.find(post)).orElseThrow();
    }

    private String lacks(String capability) {
        return "the caller lacks the capability " + capability + " on site " + site.value();
    }

    private List<String> option(String name) {
        return Jdbc.strings(
                dataSource, "select value from qp_option where site_id = ? and name = ?", site.value(), name);
    }

    @Test
    void trashingRenamesRecordsAndUnsticks() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Hello").status("publish"));
        as(() -> posts.stick(post.id()));
        Optional<PostSnapshot> before = as(() -> posts.trash(post.id()));
        assertThat(before).map(PostSnapshot::status).contains("publish");
        PostSnapshot trashed = find(post.id());
        assertThat(trashed.status()).isEqualTo("trash");
        assertThat(trashed.slug()).isEqualTo("hello__trashed");
        assertThat(trashed.meta("_wp_trash_meta_status")).containsExactly(TextNode.valueOf("publish"));
        assertThat(trashed.meta("_wp_trash_meta_time")).hasSize(1);
        assertThat(trashed.meta("_wp_trash_meta_time").get(0).asLong()).isEqualTo(NOW.getEpochSecond());
        assertThat(trashed.meta("_wp_desired_post_slug")).containsExactly(TextNode.valueOf("hello"));
        assertThat(trashed.sticky()).isFalse();
        assertThat(as(() -> posts.trash(post.id()))).isEmpty();
    }

    @Test
    void trashingAnnouncesTheUnstickTheSaveAndTheTrash() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Hello").status("publish"));
        as(() -> posts.stick(post.id()));
        recorder.seen().clear();
        as(() -> posts.trash(post.id()));
        long id = post.id().value();
        assertThat(recorder.seen())
                .containsExactly(
                        "sticky " + id + " false", "status " + id + " publish>trash", "trashed " + id + " trash");
        recorder.seen().clear();
        as(() -> posts.untrash(post.id()));
        assertThat(recorder.seen())
                .containsExactly("status " + id + " trash>draft", "restored " + id + " draft publish");
    }

    @Test
    void anAttachmentIsAnnouncedOnlyAsTrashedAndRestored() throws Exception {
        PostSnapshot attachment =
                create(PostChanges.builder().type("attachment").title("File"));
        recorder.seen().clear();
        as(() -> posts.trash(attachment.id()));
        as(() -> posts.untrash(attachment.id()));
        long id = attachment.id().value();
        assertThat(recorder.seen()).containsExactly("trashed " + id + " trash", "restored " + id + " inherit inherit");
    }

    @Test
    void aRestoreWithoutARecordedStatusAnnouncesNone() throws Exception {
        PostId bare = TestData.post(dataSource, site, "post", "trash", "bare", "Bare");
        as(() -> posts.untrash(bare));
        assertThat(recorder.seen())
                .containsExactly("status " + bare.value() + " trash>draft", "restored " + bare.value() + " draft -");
    }

    @Test
    void aSecondTrashedPostWithTheSameSlugIsSuffixed() throws Exception {
        PostSnapshot a = create(PostChanges.builder().title("Hello").status("publish"));
        as(() -> posts.trash(a.id()));
        PostSnapshot b = create(PostChanges.builder().title("Hello").status("publish"));
        assertThat(b.slug()).isEqualTo("hello");
        as(() -> posts.trash(b.id()));
        assertThat(find(b.id()).slug()).isEqualTo("hello__trashed-2");
        assertThat(find(b.id()).meta("_wp_desired_post_slug")).containsExactly(TextNode.valueOf("hello"));
    }

    @Test
    void aSlugLessDraftKeepsTheBareSuffixThroughUntrash() throws Exception {
        PostSnapshot draft = create(PostChanges.builder().title("Hello"));
        as(() -> posts.trash(draft.id()));
        assertThat(find(draft.id()).slug()).isEqualTo("__trashed");
        as(() -> posts.untrash(draft.id()));
        PostSnapshot restored = find(draft.id());
        assertThat(restored.status()).isEqualTo("draft");
        assertThat(restored.slug()).isEqualTo("__trashed");
        assertThat(restored.meta("_wp_desired_post_slug")).containsExactly(TextNode.valueOf(""));
    }

    @Test
    void trashingAFloatingDraftDatesItAndTheRestoredDraftKeepsTheDate() throws Exception {
        clock.set(Instant.parse("2026-01-10T12:00:00Z"));
        PostSnapshot draft = create(PostChanges.builder().title("Floating"));
        assertThat(draft.publishedAt()).isEmpty();
        clock.set(NOW);
        as(() -> posts.trash(draft.id()));
        assertThat(find(draft.id()).publishedAt()).contains(NOW);
        clock.set(Instant.parse("2026-01-20T12:00:00Z"));
        as(() -> posts.untrash(draft.id()));
        assertThat(find(draft.id()).publishedAt()).contains(NOW);
        PostSnapshot published = as(() ->
                posts.update(draft.id(), PostChanges.builder().status("publish").build()));
        assertThat(published.publishedAt()).contains(NOW);
    }

    @Test
    void untrashingRestoresADraftWithItsSlugAndDate() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Hello").status("publish"));
        as(() -> posts.stick(post.id()));
        as(() -> posts.trash(post.id()));
        Optional<PostSnapshot> before = as(() -> posts.untrash(post.id()));
        assertThat(before).map(PostSnapshot::status).contains("trash");
        PostSnapshot restored = find(post.id());
        assertThat(restored.status()).isEqualTo("draft");
        assertThat(restored.slug()).isEqualTo("hello");
        assertThat(restored.publishedAt()).isEqualTo(post.publishedAt());
        assertThat(restored.sticky()).isFalse();
        assertThat(restored.meta())
                .doesNotContainKeys("_wp_trash_meta_status", "_wp_trash_meta_time", "_wp_desired_post_slug");
        assertThat(as(() -> posts.untrash(post.id()))).isEmpty();
        PostSnapshot attachment =
                create(PostChanges.builder().type("attachment").title("File"));
        as(() -> posts.trash(attachment.id()));
        as(() -> posts.untrash(attachment.id()));
        assertThat(find(attachment.id()).status()).isEqualTo("inherit");
    }

    @Test
    void aRestoredSlugMayDuplicateUntilPublication() throws Exception {
        PostSnapshot first = create(PostChanges.builder().title("Hello").status("publish"));
        as(() -> posts.trash(first.id()));
        PostSnapshot second = create(PostChanges.builder().title("Hello").status("publish"));
        assertThat(second.slug()).isEqualTo("hello");
        as(() -> posts.untrash(first.id()));
        assertThat(find(first.id()).slug()).isEqualTo("hello");
        PostSnapshot republished = as(() ->
                posts.update(first.id(), PostChanges.builder().status("publish").build()));
        assertThat(republished.slug()).isEqualTo("hello-2");
    }

    @Test
    void theDesiredSlugWinsOverAGivenOne() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Old").status("publish"));
        as(() -> posts.trash(post.id()));
        PostSnapshot back = as(() -> posts.update(
                post.id(), PostChanges.builder().status("publish").slug("new").build()));
        assertThat(back.slug()).isEqualTo("old");
        assertThat(back.meta()).doesNotContainKey("_wp_desired_post_slug");
    }

    @Test
    void aLivePostClaimsItsSlugFromTrashedPostsOfSearchableTypes() throws Exception {
        PostId promo = TestData.post(dataSource, site, "post", "trash", "promo", "Promo");
        PostSnapshot page =
                create(PostChanges.builder().type("page").title("Promo").status("publish"));
        assertThat(page.slug()).isEqualTo("promo");
        assertThat(find(promo).slug()).isEqualTo("promo__trashed");
        assertThat(find(promo).meta("_wp_desired_post_slug")).containsExactly(TextNode.valueOf("promo"));
        PostId cta = TestData.post(dataSource, site, "wp_block", "trash", "cta", "CTA");
        PostSnapshot block =
                create(PostChanges.builder().type("wp_block").title("CTA").status("publish"));
        assertThat(block.slug()).isEqualTo("cta-2");
        assertThat(find(cta).slug()).isEqualTo("cta");
    }

    @Test
    void anUpdateClaimsItsSlugFromATrashedPost() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Other").status("publish"));
        PostId held = TestData.post(dataSource, site, "page", "trash", "wanted", "Wanted");
        PostSnapshot renamed = as(() ->
                posts.update(post.id(), PostChanges.builder().slug("wanted").build()));
        assertThat(renamed.slug()).isEqualTo("wanted");
        assertThat(find(held).slug()).isEqualTo("wanted__trashed");
        assertThat(find(held).meta("_wp_desired_post_slug")).containsExactly(TextNode.valueOf("wanted"));
    }

    @Test
    void trashedPostsOfAnotherSiteKeepTheirSlug() throws Exception {
        SiteId other = TestData.site(dataSource);
        PostId theirs = TestData.post(dataSource, other, "post", "trash", "shared", "Shared");
        PostSnapshot mine = create(PostChanges.builder().title("Shared").status("publish"));
        assertThat(mine.slug()).isEqualTo("shared");
        assertThat(Jdbc.strings(dataSource, "select slug from qp_post where id = ?", theirs.value()))
                .containsExactly("shared");
        assertThat(Jdbc.strings(dataSource, "select meta from qp_post where id = ?", theirs.value())
                        .get(0))
                .doesNotContain("_wp_desired_post_slug");
    }

    @Test
    void aFailedTrashChangesNothing() throws Exception {
        PostId empty = TestData.post(dataSource, site, "post", "publish", "empty", "");
        as(() -> posts.stick(empty));
        recorder.seen().clear();
        as(() -> {
            assertThat(posts.trash(empty)).isEmpty();
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            return null;
        });
        PostSnapshot kept = find(empty);
        assertThat(kept.status()).isEqualTo("publish");
        assertThat(kept.slug()).isEqualTo("empty");
        assertThat(kept.sticky()).isTrue();
        assertThat(kept.meta())
                .doesNotContainKeys("_wp_trash_meta_status", "_wp_trash_meta_time", "_wp_desired_post_slug");
        assertThat(recorder.seen()).isEmpty();
    }

    @Test
    void aTrashRefusedByASlugFilterChangesNothingAndLeavesTheTransactionUsable() throws Exception {
        PostSnapshot front =
                create(PostChanges.builder().type("page").title("Front").status("publish"));
        PostId refused = TestData.post(dataSource, site, "page", "publish", "trash-refused", "Refused");
        TestData.siteOption(dataSource, site, "show_on_front", "\"page\"");
        TestData.siteOption(dataSource, site, "page_on_front", Long.toString(refused.value()));
        as(() -> {
            assertThat(posts.trash(refused)).isEmpty();
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            assertThat(posts.trash(front.id())).isPresent();
            return null;
        });
        PostSnapshot kept = find(refused);
        assertThat(kept.status()).isEqualTo("publish");
        assertThat(kept.slug()).isEqualTo("trash-refused");
        assertThat(kept.meta()).doesNotContainKeys("_wp_trash_meta_status", "_wp_desired_post_slug");
        assertThat(option("show_on_front")).containsExactly("\"page\"");
        assertThat(option("page_on_front")).containsExactly(Long.toString(refused.value()));
        assertThat(find(front.id()).status()).isEqualTo("trash");
    }

    @Test
    void aFailedUntrashChangesNothing() throws Exception {
        PostId empty = TestData.post(dataSource, site, "post", "trash", "empty", "");
        as(() -> {
            assertThat(posts.untrash(empty)).isEmpty();
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            return null;
        });
        PostSnapshot kept = find(empty);
        assertThat(kept.status()).isEqualTo("trash");
        assertThat(kept.slug()).isEqualTo("empty");
    }

    @Test
    void aFailureAfterTheTrashWroteRollsEverythingBack() throws Exception {
        PostSnapshot page =
                create(PostChanges.builder().type("page").title("Front").status("publish"));
        TestData.siteOption(dataSource, site, "show_on_front", "\"page\"");
        TestData.siteOption(
                dataSource, site, "page_on_front", Long.toString(page.id().value()));
        recorder.refuse(true);
        AtomicInteger status = new AtomicInteger(-1);
        assertThatThrownBy(() -> as(() -> {
                    assertThatThrownBy(() -> posts.trash(page.id()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("the observer refused the trash");
                    status.set(transactions.getStatus());
                    return null;
                }))
                .isInstanceOf(QuarkusTransactionException.class)
                .hasCauseInstanceOf(RollbackException.class);
        assertThat(status.get()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
        recorder.refuse(false);
        PostSnapshot kept = find(page.id());
        assertThat(kept.status()).isEqualTo("publish");
        assertThat(kept.slug()).isEqualTo("front");
        assertThat(kept.meta()).doesNotContainKeys("_wp_trash_meta_status", "_wp_desired_post_slug");
        assertThat(option("show_on_front")).containsExactly("\"page\"");
        assertThat(option("page_on_front"))
                .containsExactly(Long.toString(page.id().value()));
    }

    @Test
    void aRefusalAfterTheSaveWroteIsNotTakenForARefusedSave() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Hello").status("publish"));
        PostSnapshot trashed = create(PostChanges.builder().title("Gone").status("publish"));
        as(() -> posts.trash(trashed.id()));
        recorder.refuseSave(true);
        for (Callable<Optional<PostSnapshot>> call : List.<Callable<Optional<PostSnapshot>>>of(
                () -> posts.trash(post.id()), () -> posts.untrash(trashed.id()))) {
            AtomicInteger status = new AtomicInteger(-1);
            assertThatThrownBy(() -> as(() -> {
                        assertThatThrownBy(call::call)
                                .isInstanceOfSatisfying(
                                        PostException.class,
                                        e -> assertThat(e.code()).isEqualTo("save_refused"))
                                .hasMessage("the observer refused the save");
                        status.set(transactions.getStatus());
                        return null;
                    }))
                    .isInstanceOf(QuarkusTransactionException.class)
                    .hasCauseInstanceOf(RollbackException.class);
            assertThat(status.get()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
        }
        recorder.refuseSave(false);
        assertThat(find(post.id()).status()).isEqualTo("publish");
        assertThat(find(trashed.id()).status()).isEqualTo("trash");
    }

    @Test
    void aFailureAfterTheUntrashWroteRollsBackItsClaim() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Hello").status("publish"));
        as(() -> posts.trash(post.id()));
        PostId plain = TestData.post(dataSource, site, "post", "trash", "hello", "Plain");
        recorder.refuse(true);
        AtomicInteger status = new AtomicInteger(-1);
        assertThatThrownBy(() -> as(() -> {
                    assertThatThrownBy(() -> posts.untrash(post.id()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("the observer refused the restore");
                    status.set(transactions.getStatus());
                    return null;
                }))
                .isInstanceOf(QuarkusTransactionException.class)
                .hasCauseInstanceOf(RollbackException.class);
        assertThat(status.get()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
        recorder.refuse(false);
        assertThat(find(plain).slug()).isEqualTo("hello");
        assertThat(find(plain).meta()).doesNotContainKey("_wp_desired_post_slug");
        PostSnapshot kept = find(post.id());
        assertThat(kept.status()).isEqualTo("trash");
        assertThat(kept.slug()).isEqualTo("hello__trashed");
        assertThat(kept.meta("_wp_trash_meta_status")).containsExactly(TextNode.valueOf("publish"));
    }

    @Test
    void aRefusalAfterAClaimRollsTheClaimBack() throws Exception {
        PostId held = TestData.post(dataSource, site, "post", "trash", "claim-refused", "Held");
        PostSnapshot post = create(PostChanges.builder().title("Other").status("publish"));
        for (Callable<PostSnapshot> save : List.<Callable<PostSnapshot>>of(
                () -> posts.create(PostChanges.builder()
                        .title("New")
                        .slug("claim-refused")
                        .status("publish")
                        .build()),
                () -> posts.update(
                        post.id(), PostChanges.builder().slug("claim-refused").build()))) {
            AtomicInteger status = new AtomicInteger(-1);
            assertThatThrownBy(() -> as(() -> {
                        assertThatThrownBy(save::call)
                                .isInstanceOfSatisfying(
                                        PostException.class,
                                        e -> assertThat(e.code()).isEqualTo("slug_refused"))
                                .hasMessage("the slug claim-refused is refused");
                        status.set(transactions.getStatus());
                        return null;
                    }))
                    .isInstanceOf(QuarkusTransactionException.class)
                    .hasCauseInstanceOf(RollbackException.class);
            assertThat(status.get()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
            assertThat(find(held).slug()).isEqualTo("claim-refused");
            assertThat(find(held).meta()).doesNotContainKey("_wp_desired_post_slug");
        }
        assertThat(find(post.id()).slug()).isEqualTo("other");
    }

    @Test
    void trashingTheFrontPageResetsTheReadingSettings() throws Exception {
        PostSnapshot front =
                create(PostChanges.builder().type("page").title("Front").status("publish"));
        PostSnapshot blog =
                create(PostChanges.builder().type("page").title("Blog").status("publish"));
        TestData.siteOption(dataSource, site, "show_on_front", "\"page\"");
        TestData.siteOption(
                dataSource, site, "page_on_front", Long.toString(front.id().value()));
        TestData.siteOption(
                dataSource, site, "page_for_posts", Long.toString(blog.id().value()));
        recorder.seen().clear();
        as(() -> posts.trash(front.id()));
        long id = front.id().value();
        assertThat(recorder.seen())
                .containsExactly(
                        "option show_on_front",
                        "option page_on_front",
                        "status " + id + " publish>trash",
                        "trashed " + id + " trash");
        assertThat(option("show_on_front")).containsExactly("\"posts\"");
        assertThat(option("page_on_front")).containsExactly("0");
        assertThat(option("page_for_posts"))
                .containsExactly(Long.toString(blog.id().value()));
        as(() -> posts.trash(blog.id()));
        assertThat(option("page_for_posts")).containsExactly("0");
    }

    @Test
    void trashingThePrivacyPageLeavesItsSetting() throws Exception {
        PostSnapshot privacy =
                create(PostChanges.builder().type("page").title("Privacy").status("publish"));
        String value = Long.toString(privacy.id().value());
        TestData.siteOption(dataSource, site, "wp_page_for_privacy_policy", value);
        as(() -> posts.trash(privacy.id()));
        assertThat(find(privacy.id()).status()).isEqualTo("trash");
        assertThat(option("wp_page_for_privacy_policy")).containsExactly(value);
    }

    @Test
    void theReadingSettingsAreReadAsPhpCastsThemToNumbers() throws Exception {
        List<String> matching = List.of(
                "\"%dabc\"",
                "\" %d\"", "\"\\t\\n%d\"", "\"%d \"", "\"+%d\"", "\"0%d\"", "\"%d.9\"", "%d.0", "\"%d.0e0x\"");
        for (String format : matching) {
            PostSnapshot front =
                    create(PostChanges.builder().type("page").title("Front").status("publish"));
            PostSnapshot blog =
                    create(PostChanges.builder().type("page").title("Blog").status("publish"));
            TestData.siteOption(dataSource, site, "show_on_front", "\"page\"");
            TestData.siteOption(
                    dataSource,
                    site,
                    "page_on_front",
                    String.format(format, front.id().value()));
            TestData.siteOption(
                    dataSource,
                    site,
                    "page_for_posts",
                    String.format(format, blog.id().value()));
            as(() -> posts.trash(front.id()));
            as(() -> posts.trash(blog.id()));
            assertThat(option("show_on_front")).as(format).containsExactly("\"posts\"");
            assertThat(option("page_on_front")).as(format).containsExactly("0");
            assertThat(option("page_for_posts")).as(format).containsExactly("0");
        }
        PostSnapshot kept =
                create(PostChanges.builder().type("page").title("Kept").status("publish"));
        String other = "\"x" + kept.id().value() + "\"";
        TestData.siteOption(dataSource, site, "show_on_front", "\"page\"");
        TestData.siteOption(dataSource, site, "page_on_front", other);
        as(() -> posts.trash(kept.id()));
        assertThat(option("show_on_front")).containsExactly("\"page\"");
        assertThat(option("page_on_front")).containsExactly(other);
    }

    @Test
    void aSuccessfulUntrashClaimsItsDesiredSlug() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Hello").status("publish"));
        as(() -> posts.trash(post.id()));
        PostId plain = TestData.post(dataSource, site, "page", "trash", "hello", "Plain");
        as(() -> posts.untrash(post.id()));
        assertThat(find(post.id()).slug()).isEqualTo("hello");
        assertThat(find(plain).slug()).isEqualTo("hello__trashed");
        assertThat(find(plain).meta("_wp_desired_post_slug")).containsExactly(TextNode.valueOf("hello"));
    }

    @Test
    void aDraftWithAnExplicitSlugClaimsIt() throws Exception {
        PostId first = TestData.post(dataSource, site, "post", "trash", "draft-slug", "First");
        PostSnapshot draft = create(PostChanges.builder().title("Draft").slug("draft-slug"));
        assertThat(draft.status()).isEqualTo("draft");
        assertThat(draft.slug()).isEqualTo("draft-slug");
        assertThat(find(first).slug()).isEqualTo("draft-slug__trashed");
        PostId second = TestData.post(dataSource, site, "attachment", "trash", "kept-slug", "Second");
        PostSnapshot updated = as(() ->
                posts.update(draft.id(), PostChanges.builder().slug("kept-slug").build()));
        assertThat(updated.status()).isEqualTo("draft");
        assertThat(updated.slug()).isEqualTo("kept-slug");
        assertThat(find(second).slug()).isEqualTo("kept-slug__trashed");
        assertThat(find(second).meta("_wp_desired_post_slug")).containsExactly(TextNode.valueOf("kept-slug"));
    }

    @Test
    void everyTrashedHolderOfASearchableTypeIsRenamed() throws Exception {
        PostId post = TestData.post(dataSource, site, "post", "trash", "shared", "Post");
        PostId page = TestData.post(dataSource, site, "page", "trash", "shared", "Page");
        PostId attachment = TestData.post(dataSource, site, "attachment", "trash", "shared", "File");
        create(PostChanges.builder().title("Shared").status("publish"));
        for (PostId holder : List.of(post, page, attachment)) {
            assertThat(find(holder).slug()).isEqualTo("shared__trashed");
            assertThat(find(holder).meta("_wp_desired_post_slug")).containsExactly(TextNode.valueOf("shared"));
        }
    }

    @Test
    void retrashingASuffixedSlugKeepsItAndRecordsTheStatusAgain() throws Exception {
        PostSnapshot draft = create(PostChanges.builder().title("Hello"));
        as(() -> posts.trash(draft.id()));
        as(() -> posts.untrash(draft.id()));
        as(() -> posts.trash(draft.id()));
        PostSnapshot again = find(draft.id());
        assertThat(again.slug()).isEqualTo("__trashed");
        assertThat(again.meta("_wp_desired_post_slug")).containsExactly(TextNode.valueOf(""));
        assertThat(again.meta("_wp_trash_meta_status")).containsExactly(TextNode.valueOf("draft"));
        PostSnapshot published = create(PostChanges.builder().title("Twice").status("publish"));
        as(() -> posts.trash(published.id()));
        Jdbc.update(
                dataSource,
                "update qp_post set status = 'publish' where id = ?",
                published.id().value());
        as(() -> posts.trash(published.id()));
        PostSnapshot twice = find(published.id());
        assertThat(twice.slug()).isEqualTo("twice__trashed");
        assertThat(twice.meta("_wp_desired_post_slug")).containsExactly(TextNode.valueOf("twice"));
        assertThat(twice.meta("_wp_trash_meta_status"))
                .containsExactly(TextNode.valueOf("publish"), TextNode.valueOf("publish"));
    }

    @Test
    void trashingAPostOfAnotherTypeLeavesTheReadingSettings() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Plain").status("publish"));
        TestData.siteOption(dataSource, site, "show_on_front", "\"page\"");
        TestData.siteOption(
                dataSource, site, "page_on_front", Long.toString(post.id().value()));
        TestData.siteOption(
                dataSource, site, "page_for_posts", Long.toString(post.id().value()));
        as(() -> posts.trash(post.id()));
        assertThat(option("show_on_front")).containsExactly("\"page\"");
        assertThat(option("page_on_front"))
                .containsExactly(Long.toString(post.id().value()));
        assertThat(option("page_for_posts"))
                .containsExactly(Long.toString(post.id().value()));
    }

    @Test
    void trashingNeedsDeletePost() throws Exception {
        UserId author = TestData.memberOfMain(dataSource, "author");
        UserId contributor = TestData.memberOfMain(dataSource, "contributor");
        PostSnapshot editors = create(PostChanges.builder().title("Editors").status("publish"));
        assertThatThrownBy(() -> Identities.onSite(site, Identities.user(author), () -> posts.trash(editors.id())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("delete_post"));
        assertThatThrownBy(() -> Identities.onSite(site, Identities.anonymous(), () -> posts.trash(editors.id())))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(lacks("delete_post"));
        assertThat(find(editors.id()).status()).isEqualTo("publish");
        PostSnapshot own = Identities.onSite(
                site,
                Identities.user(contributor),
                () -> posts.create(PostChanges.builder().title("Mine").build()));
        assertThat(Identities.onSite(site, Identities.user(contributor), () -> posts.trash(own.id())))
                .isPresent();
    }

    @Test
    void untrashingNeedsDeletePost() throws Exception {
        UserId author = TestData.memberOfMain(dataSource, "author");
        UserId contributor = TestData.memberOfMain(dataSource, "contributor");
        PostSnapshot editors = create(PostChanges.builder().title("Editors").status("publish"));
        as(() -> posts.trash(editors.id()));
        assertThatThrownBy(() -> Identities.onSite(site, Identities.user(author), () -> posts.untrash(editors.id())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("delete_post"));
        assertThatThrownBy(() -> Identities.onSite(site, Identities.anonymous(), () -> posts.untrash(editors.id())))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(lacks("delete_post"));
        assertThat(find(editors.id()).status()).isEqualTo("trash");
        PostSnapshot own = Identities.onSite(
                site,
                Identities.user(contributor),
                () -> posts.create(PostChanges.builder().title("Mine").build()));
        Identities.onSite(site, Identities.user(contributor), () -> posts.trash(own.id()));
        assertThat(Identities.onSite(site, Identities.user(contributor), () -> posts.untrash(own.id())))
                .isPresent();
        assertThat(find(own.id()).status()).isEqualTo("draft");
    }

    @Test
    void aMissingPostIsInvalid() throws Exception {
        PostId missing = PostId.of(999_999_999L);
        for (Callable<Optional<PostSnapshot>> call :
                List.<Callable<Optional<PostSnapshot>>>of(() -> posts.trash(missing), () -> posts.untrash(missing))) {
            assertThatThrownBy(() -> as(call))
                    .isInstanceOfSatisfying(
                            PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"))
                    .hasMessage("Invalid post ID.");
        }
    }

    @Test
    void trashingAParentLeavesItsChildren() throws Exception {
        PostSnapshot parent =
                create(PostChanges.builder().type("page").title("Team").status("publish"));
        PostSnapshot child = create(PostChanges.builder()
                .type("page")
                .title("Alice")
                .status("publish")
                .parent(parent.id()));
        as(() -> posts.trash(parent.id()));
        assertThat(find(child.id()).parent()).contains(parent.id());
    }
}
