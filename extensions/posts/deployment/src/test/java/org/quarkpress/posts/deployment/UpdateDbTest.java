package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

/** Updating merges the stored post with the changes and saves it again as WordPress's wp_update_post does. */
class UpdateDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(UpdateDbTest.class, TestClock.class);

    static final Instant T0 = Instant.parse("2026-01-14T12:00:00Z");
    static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    TestClock clock;

    @Inject
    TransactionManager transactions;

    SiteId site;
    UserId editor;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        site = TestData.siteIn(dataSource, "Europe/Paris");
        editor = TestData.memberOfMain(dataSource, "editor");
    }

    private PostSnapshot create(PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(editor), () -> posts.create(changes.build()));
    }

    private PostSnapshot update(UserId user, PostId post, PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(user), () -> posts.update(post, changes.build()));
    }

    private PostSnapshot update(PostId post, PostChanges.Builder changes) throws Exception {
        return update(editor, post, changes);
    }

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private static LocalDateTime local(String text) {
        return LocalDateTime.parse(text.replace(' ', 'T'));
    }

    @Test
    void aPostCannotBeMovedToTheRevisionType() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Kept").status("publish"));
        assertThatThrownBy(() -> update(post.id(), PostChanges.builder().type("revision")))
                .isInstanceOfSatisfying(
                        PostException.class,
                        refused -> assertThat(refused.code()).isEqualTo("invalid_post_type"));
        assertThat(Jdbc.strings(
                        dataSource,
                        "select type from qp_post where id = ?",
                        post.id().value()))
                .containsExactly("post");
    }

    @Test
    void anUpdateMergesTheStoredPostAndIsModifiedNow() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("A").content("x").status("publish"));
        clock.set(NOW);
        PostSnapshot updated = update(post.id(), PostChanges.builder().excerpt("e"));
        assertThat(updated.title()).isEqualTo("A");
        assertThat(updated.content()).isEqualTo("x");
        assertThat(updated.excerpt()).isEqualTo("e");
        assertThat(updated.slug()).isEqualTo("a");
        assertThat(updated.publishedAt()).isEqualTo(post.publishedAt());
        assertThat(updated.modifiedAt()).isEqualTo(NOW);
        assertThat(updated.guid()).isEqualTo(post.guid());
        assertThat(updated.createdAt()).isEqualTo(post.createdAt());
        assertThat(Jdbc.strings(
                        dataSource,
                        "select excerpt from qp_post where id = ?",
                        post.id().value()))
                .containsExactly("e");
    }

    @Test
    void aMissingPostOrAPostOfAnotherSiteIsInvalid() {
        SiteId other = TestData.site(dataSource);
        PostId elsewhere = TestData.post(dataSource, other, "post", "publish", "x", "X");
        assertThatThrownBy(() ->
                        update(PostId.of(Long.MAX_VALUE), PostChanges.builder().title("t")))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"))
                .hasMessage("Invalid post ID.");
        assertThatThrownBy(() -> update(elsewhere, PostChanges.builder().title("t")))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"))
                .hasMessage("Invalid post ID.");
        assertThat(Jdbc.strings(dataSource, "select title from qp_post where id = ?", elsewhere.value()))
                .containsExactly("X");
    }

    @Test
    void anUpdateThatEmptiesThePostIsRefusedAndChangesNothing() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Full"));
        assertThatThrownBy(() -> update(post.id(), PostChanges.builder().title("  ")))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("empty_content"))
                .hasMessage("Content, title, and excerpt are empty.");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select title from qp_post where id = ?",
                        post.id().value()))
                .containsExactly("Full");
    }

    @Test
    void aFloatingDraftIsDatedNowOnEveryUpdate() throws Exception {
        PostSnapshot draft = create(PostChanges.builder().title("Draft"));
        assertThat(draft.localDate(PARIS)).isEqualTo(local("2026-01-14 13:00:00"));
        clock.set(NOW);
        PostSnapshot updated = update(draft.id(), PostChanges.builder().title("x"));
        assertThat(updated.publishedAt()).isEmpty();
        assertThat(updated.localDate(PARIS)).isEqualTo(local("2026-01-15 13:00:00"));
    }

    @Test
    void publishingAFloatingDraftDatesItNowUnlessEditDateIsSet() throws Exception {
        PostSnapshot draft = create(PostChanges.builder().title("Draft"));
        clock.set(NOW);
        PostSnapshot published = update(draft.id(), PostChanges.builder().status("publish"));
        assertThat(published.status()).isEqualTo("publish");
        assertThat(published.publishedAt()).contains(NOW);

        PostSnapshot other = create(PostChanges.builder().title("Other"));
        PostSnapshot ignored =
                update(other.id(), PostChanges.builder().status("publish").date(local("2026-02-01 09:00:00")));
        assertThat(ignored.publishedAt()).contains(NOW);
        assertThat(ignored.status()).isEqualTo("publish");

        PostSnapshot third = create(PostChanges.builder().title("Third"));
        PostSnapshot scheduled = update(
                third.id(),
                PostChanges.builder()
                        .status("publish")
                        .date(local("2026-02-01 09:00:00"))
                        .editDate(true));
        assertThat(scheduled.status()).isEqualTo("future");
        assertThat(scheduled.publishedAt()).contains(Instant.parse("2026-02-01T08:00:00Z"));
    }

    @Test
    void editDateWithoutADateKeepsTheDraftsFloatingDate() throws Exception {
        PostSnapshot draft = create(PostChanges.builder().title("Draft"));
        clock.set(NOW);
        PostSnapshot published =
                update(draft.id(), PostChanges.builder().status("publish").editDate(true));
        assertThat(published.status()).isEqualTo("publish");
        assertThat(published.publishedAt()).contains(T0);
    }

    @Test
    void aDraftWithAGmtDateDoesNotFloat() throws Exception {
        PostSnapshot draft =
                create(PostChanges.builder().title("Dated").dateGmt(Instant.parse("2026-01-10T08:00:00Z")));
        clock.set(NOW);
        PostSnapshot updated = update(draft.id(), PostChanges.builder().title("Still dated"));
        assertThat(updated.publishedAt()).contains(Instant.parse("2026-01-10T08:00:00Z"));
        PostSnapshot redated = update(draft.id(), PostChanges.builder().date(local("2026-01-11 10:00:00")));
        assertThat(redated.publishedAt()).contains(Instant.parse("2026-01-11T09:00:00Z"));
        PostSnapshot published = update(draft.id(), PostChanges.builder().status("publish"));
        assertThat(published.publishedAt()).contains(Instant.parse("2026-01-11T09:00:00Z"));
    }

    /** A documented difference: WordPress would keep the old GMT date, so the post would stay published. */
    @Test
    void aLocalDateGivenAloneIsConvertedWithTheSiteZone() throws Exception {
        clock.set(NOW);
        PostSnapshot post =
                create(PostChanges.builder().title("Moved").status("publish").date(local("2026-01-10 10:00:00")));
        PostSnapshot moved = update(post.id(), PostChanges.builder().date(local("2026-03-01 10:00:00")));
        assertThat(moved.publishedAt()).contains(Instant.parse("2026-03-01T09:00:00Z"));
        assertThat(moved.status()).isEqualTo("future");
    }

    @Test
    void slugsAreKeptRegeneratedOrSanitizedAsWordPressDoes() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Hello World").status("publish"));
        assertThat(update(post.id(), PostChanges.builder().title("Goodbye")).slug())
                .isEqualTo("hello-world");
        assertThat(update(post.id(), PostChanges.builder().slug("")).slug()).isEqualTo("goodbye");
        assertThat(update(post.id(), PostChanges.builder().slug("Custom Slug")).slug())
                .isEqualTo("custom-slug");
        Jdbc.update(
                dataSource,
                "update qp_post set slug = 'caf%c3%a9' where id = ?",
                post.id().value());
        String cafe = "caf" + cp(0xE9);
        assertThat(update(post.id(), PostChanges.builder().slug(cafe)).slug()).isEqualTo("caf%c3%a9");
        assertThat(update(post.id(), PostChanges.builder().slug("Caf" + cp(0xE9)))
                        .slug())
                .isEqualTo("caf%c3%a9");
        assertThat(update(post.id(), PostChanges.builder().slug("caf%c3%a9")).slug())
                .isEqualTo("caf%c3%a9");
        assertThat(update(post.id(), PostChanges.builder().slug(cafe + "!")).slug())
                .isEqualTo("cafe");
    }

    @Test
    void aGivenSlugIsMadeUniqueAgainstOtherPosts() throws Exception {
        create(PostChanges.builder().title("Taken").status("publish"));
        PostSnapshot post = create(PostChanges.builder().title("Mine").status("publish"));
        assertThat(update(post.id(), PostChanges.builder().slug("taken")).slug())
                .isEqualTo("taken-2");
        assertThat(update(post.id(), PostChanges.builder().title("Again")).slug())
                .isEqualTo("taken-2");
    }

    @Test
    void aPendingPostKeepsItsSlugOnlyForCallersWhoCanPublishIt() throws Exception {
        UserId contributor = TestData.memberOfMain(dataSource, "contributor");
        PostSnapshot pending = Identities.onSite(
                site,
                Identities.user(contributor),
                () -> posts.create(PostChanges.builder()
                        .title("Review me")
                        .status("pending")
                        .build()));
        assertThat(update(contributor, pending.id(), PostChanges.builder().slug("mine"))
                        .slug())
                .isEmpty();
        assertThat(update(editor, pending.id(), PostChanges.builder().slug("fixed"))
                        .slug())
                .isEqualTo("fixed");
    }

    @Test
    void anEmptyCommentStatusClosesCommentsOnUpdate() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Open"));
        PostSnapshot updated =
                update(post.id(), PostChanges.builder().commentStatus("").pingStatus(""));
        assertThat(updated.commentStatus()).isEqualTo("closed");
        assertThat(updated.pingStatus()).isEqualTo("open");
    }

    @Test
    void everyUpdateFiltersEveryFieldWithTheCallersSettings() throws Exception {
        UserId author = TestData.memberOfMain(dataSource, "author");
        PostSnapshot post = create(PostChanges.builder()
                .title("<b>T</b> & co")
                .content("<iframe src=\"x\"></iframe>ok")
                .excerpt("<script>x</script>e")
                .author(author));
        assertThat(post.title()).isEqualTo("<b>T</b> & co");
        assertThat(post.content()).isEqualTo("<iframe src=\"x\"></iframe>ok");
        assertThat(post.excerpt()).isEqualTo("<script>x</script>e");
        PostSnapshot updated = update(author, post.id(), PostChanges.builder().menuOrder(3));
        assertThat(updated.title()).isEqualTo("<b>T</b> &amp; co");
        assertThat(updated.content()).isEqualTo("ok");
        assertThat(updated.excerpt()).isEqualTo("xe");
        assertThat(updated.menuOrder()).isEqualTo(3);
    }

    @Test
    void publishedFlatPostsRecordTheirOldSlugsAndDates() throws Exception {
        clock.set(NOW);
        PostSnapshot post =
                create(PostChanges.builder().title("Alpha").status("publish").date(local("2026-01-10 10:00:00")));
        PostSnapshot beta = update(post.id(), PostChanges.builder().slug("beta"));
        assertThat(beta.meta("_wp_old_slug")).containsExactly(TextNode.valueOf("alpha"));
        PostSnapshot back = update(post.id(), PostChanges.builder().slug("alpha"));
        assertThat(back.meta("_wp_old_slug")).containsExactly(TextNode.valueOf("beta"));
        PostSnapshot moved = update(post.id(), PostChanges.builder().date(local("2026-01-12 10:00:00")));
        assertThat(moved.meta("_wp_old_date")).containsExactly(TextNode.valueOf("2026-01-10"));
        PostSnapshot returned = update(post.id(), PostChanges.builder().date(local("2026-01-10 11:00:00")));
        assertThat(returned.meta("_wp_old_date")).containsExactly(TextNode.valueOf("2026-01-12"));
        assertThat(returned.meta("_wp_old_slug")).containsExactly(TextNode.valueOf("beta"));

        PostSnapshot draft = create(PostChanges.builder().title("Gamma"));
        assertThat(update(draft.id(), PostChanges.builder().slug("delta")).meta("_wp_old_slug"))
                .isEmpty();
        PostSnapshot page =
                create(PostChanges.builder().type("page").title("Page").status("publish"));
        assertThat(update(page.id(), PostChanges.builder().slug("renamed")).meta("_wp_old_slug"))
                .isEmpty();
    }

    @Test
    void attachmentsRecordTheirOldSlugs() throws Exception {
        PostSnapshot file = create(PostChanges.builder().type("attachment").title("Photo"));
        assertThat(file.status()).isEqualTo("inherit");
        assertThat(update(file.id(), PostChanges.builder().slug("picture")).meta("_wp_old_slug"))
                .containsExactly(TextNode.valueOf("photo"));
    }

    @Test
    void aStaleVersionIsRefusedAndChangesNothing() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Versioned"));
        int seen = post.version();
        PostSnapshot first =
                update(post.id(), PostChanges.builder().title("First").expectedVersion(seen));
        assertThat(first.version()).isGreaterThan(seen);
        assertThatThrownBy(() -> update(
                        post.id(),
                        PostChanges.builder().title("Second").content("given").expectedVersion(seen)))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessage("post " + post.id().value() + " changed since version " + seen + "; it is now at version "
                        + first.version());
        assertThat(Jdbc.strings(
                        dataSource,
                        "select title from qp_post where id = ?",
                        post.id().value()))
                .containsExactly("First");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select content from qp_post where id = ?",
                        post.id().value()))
                .containsExactly("");
        assertThat(Jdbc.count(
                        dataSource,
                        "select version from qp_post where id = ?",
                        post.id().value()))
                .isEqualTo(first.version());
    }

    @Test
    void aStaleVersionRefusalLeavesTheCallersTransactionUsable() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Versioned"));
        int seen = post.version();
        PostSnapshot first = update(post.id(), PostChanges.builder().title("First"));
        PostSnapshot retried = Identities.onSite(site, Identities.user(editor), () -> {
            assertThatThrownBy(() -> posts.update(
                            post.id(),
                            PostChanges.builder()
                                    .title("Stale")
                                    .expectedVersion(seen)
                                    .build()))
                    .isInstanceOf(OptimisticLockException.class)
                    .hasMessage("post " + post.id().value() + " changed since version " + seen
                            + "; it is now at version " + first.version());
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            return posts.update(
                    post.id(),
                    PostChanges.builder()
                            .title("Merged")
                            .expectedVersion(first.version())
                            .build());
        });
        assertThat(retried.title()).isEqualTo("Merged");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select title from qp_post where id = ?",
                        post.id().value()))
                .containsExactly("Merged");
    }

    @Test
    void theTypeCanChange() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("Was a post").status("publish"));
        PostSnapshot page = update(post.id(), PostChanges.builder().type("page"));
        assertThat(page.type()).isEqualTo("page");
        assertThat(page.slug()).isEqualTo("was-a-post");
    }

    @Test
    void scheduledPostsWhoseDateHasComeArePublishedWhenSaved() throws Exception {
        PostSnapshot post =
                create(PostChanges.builder().title("Soon").status("publish").dateGmt(T0.plus(Duration.ofHours(1))));
        assertThat(post.status()).isEqualTo("future");
        clock.set(NOW);
        assertThat(update(post.id(), PostChanges.builder().title("Soon again")).status())
                .isEqualTo("publish");
    }
}
