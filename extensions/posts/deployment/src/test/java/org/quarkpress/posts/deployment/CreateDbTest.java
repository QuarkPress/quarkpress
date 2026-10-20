package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
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
import org.quarkpress.persistence.runtime.HeldLocks;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;

class CreateDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(CreateDbTest.class, TestClock.class);

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
        clock.set(NOW);
        site = TestData.siteIn(dataSource, "Europe/Paris");
        editor = TestData.memberOfMain(dataSource, "editor");
    }

    private PostSnapshot create(UserId user, PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(user), () -> posts.create(changes.build()));
    }

    private PostSnapshot asSystem(PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.system(), () -> posts.create(changes.build()));
    }

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private static LocalDateTime local(String text) {
        return LocalDateTime.parse(text.replace(' ', 'T'));
    }

    @Test
    void anInsertTakesWordPressDefaults() throws Exception {
        PostSnapshot post = create(editor, PostChanges.builder().title("Hello"));
        assertThat(post.type()).isEqualTo("post");
        assertThat(post.status()).isEqualTo("draft");
        assertThat(post.slug()).isEmpty();
        assertThat(post.author()).contains(editor);
        assertThat(post.commentStatus()).isEqualTo("open");
        assertThat(post.pingStatus()).isEqualTo("open");
        assertThat(post.password()).isEmpty();
        assertThat(post.parent()).isEmpty();
        assertThat(post.menuOrder()).isZero();
        assertThat(post.publishedAt()).isEmpty();
        assertThat(post.modifiedAt()).isEqualTo(NOW);
        assertThat(post.localDate(PARIS)).isEqualTo(local("2026-01-15 13:00:00"));
        assertThat(post.sticky()).isFalse();
        assertThat(post.meta()).isEmpty();
        assertThat(post.guid()).endsWith("/?p=" + post.id().value()).startsWith("http://posts-");
        PostSnapshot page = create(editor, PostChanges.builder().type("page").title("Page"));
        assertThat(page.guid()).endsWith(".test/?page_id=" + page.id().value());
        PostSnapshot file =
                create(editor, PostChanges.builder().type("attachment").title("File"));
        assertThat(file.guid()).endsWith(".test/?attachment_id=" + file.id().value());
        PostSnapshot block =
                create(editor, PostChanges.builder().type("wp_block").title("Block"));
        assertThat(block.guid())
                .endsWith(".test/?post_type=wp_block&p=" + block.id().value());
        assertThat(Identities.onSite(site, Identities.user(editor), () -> posts.find(post.id())))
                .contains(post);
    }

    @Test
    void emptyPostsAreRefusedUnlessTheTypeLacksAField() throws Exception {
        assertThatThrownBy(() -> create(editor, PostChanges.builder()))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("empty_content"))
                .hasMessage("Content, title, and excerpt are empty.");
        assertThatThrownBy(() -> create(editor, PostChanges.builder().title("0").content("0")))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("empty_content"));
        assertThatThrownBy(() -> create(editor, PostChanges.builder().title("   ")))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("empty_content"));
        assertThatThrownBy(() -> create(editor, PostChanges.builder().type("wp_block")))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("empty_content"));
        PostSnapshot page = create(editor, PostChanges.builder().type("page"));
        assertThat(page.status()).isEqualTo("draft");
        assertThat(page.slug()).isEmpty();
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post where site_id = ?", site.value()))
                .isEqualTo(1);
    }

    @Test
    void commentAndPingStatusesDefaultByType() throws Exception {
        PostSnapshot page = create(editor, PostChanges.builder().type("page").title("P"));
        assertThat(page.commentStatus()).isEqualTo("closed");
        assertThat(page.pingStatus()).isEqualTo("closed");
        PostSnapshot attachment =
                create(editor, PostChanges.builder().type("attachment").title("A"));
        assertThat(attachment.commentStatus()).isEqualTo("open");
        assertThat(attachment.pingStatus()).isEqualTo("closed");
        assertThat(attachment.status()).isEqualTo("inherit");
        PostSnapshot block =
                create(editor, PostChanges.builder().type("wp_block").title("B"));
        assertThat(block.commentStatus()).isEqualTo("closed");
        PostSnapshot given = create(
                editor, PostChanges.builder().title("G").commentStatus("Open").pingStatus("yes"));
        assertThat(given.commentStatus()).isEqualTo("open");
        assertThat(given.pingStatus()).isEqualTo("yes");
    }

    @Test
    void theAuthorIsTheCallerUnlessGiven() throws Exception {
        assertThat(asSystem(PostChanges.builder().title("Cron")).author()).isEmpty();
        UserId other = TestData.profile(dataSource, "other");
        assertThat(create(editor, PostChanges.builder().title("For other").author(other))
                        .author())
                .contains(other);
        assertThat(create(editor, PostChanges.builder().title("Nobody").author(0))
                        .author())
                .isEmpty();
        assertThatThrownBy(() ->
                        create(editor, PostChanges.builder().title("Ghost").author(Long.MAX_VALUE)))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_author"));
    }

    @Test
    void slugsAreGeneratedOnlyForStatusesThatHaveThem() throws Exception {
        assertThat(create(editor, PostChanges.builder().title("Hello World").status("publish"))
                        .slug())
                .isEqualTo("hello-world");
        assertThat(create(editor, PostChanges.builder().title("Hello World").status("draft"))
                        .slug())
                .isEmpty();
        assertThat(create(editor, PostChanges.builder().title("Hello World").slug("Custom Slug"))
                        .slug())
                .isEqualTo("custom-slug");
        assertThat(create(editor, PostChanges.builder().title("Zero").slug("0").status("publish"))
                        .slug())
                .isEqualTo("zero");
        assertThat(create(editor, PostChanges.builder().title("Hello World").status("publish"))
                        .slug())
                .isEqualTo("hello-world-2");
        assertThat(create(editor, PostChanges.builder().title("Same").slug("same"))
                        .slug())
                .isEqualTo("same");
        assertThat(create(editor, PostChanges.builder().title("Same").slug("same"))
                        .slug())
                .isEqualTo("same");
        assertThat(create(editor, PostChanges.builder().title("Feed").status("publish"))
                        .slug())
                .isEqualTo("feed-2");
        assertThat(create(
                                editor,
                                PostChanges.builder()
                                        .title("About")
                                        .type("page")
                                        .status("publish"))
                        .slug())
                .isEqualTo("about");
        assertThat(create(editor, PostChanges.builder().title("About").status("publish"))
                        .slug())
                .isEqualTo("about");
    }

    @Test
    void aPublishedPostWithoutTitleTakesItsId() throws Exception {
        PostSnapshot post = create(editor, PostChanges.builder().content("x").status("publish"));
        assertThat(post.slug()).isEqualTo(Long.toString(post.id().value()));
        PostSnapshot page =
                create(editor, PostChanges.builder().type("page").title("!!!").status("publish"));
        assertThat(page.slug()).isEqualTo(page.id().value() + "-2");
        TestData.siteOption(dataSource, site, "permalink_structure", "\"/%postname%/\"");
        PostSnapshot numbered =
                create(editor, PostChanges.builder().content("y").status("publish"));
        assertThat(numbered.slug()).isEqualTo(numbered.id().value() + "-2");
        PostSnapshot zero =
                create(editor, PostChanges.builder().title("0").content("z").status("publish"));
        assertThat(zero.slug()).isEqualTo("0");
    }

    @Test
    void statusesFollowTheGmtDate() throws Exception {
        assertThat(create(
                                editor,
                                PostChanges.builder()
                                        .title("Soon")
                                        .status("publish")
                                        .dateGmt(NOW.plus(Duration.ofMinutes(30))))
                        .status())
                .isEqualTo("future");
        assertThat(create(
                                editor,
                                PostChanges.builder()
                                        .title("Late")
                                        .status("future")
                                        .dateGmt(NOW.minus(Duration.ofDays(1))))
                        .status())
                .isEqualTo("publish");
        assertThat(create(editor, PostChanges.builder().title("Undated").status("future"))
                        .status())
                .isEqualTo("publish");
        assertThat(create(
                                editor,
                                PostChanges.builder()
                                        .title("Private")
                                        .status("private")
                                        .date(local("2027-01-01 00:00:00")))
                        .status())
                .isEqualTo("private");
        assertThat(create(
                                editor,
                                PostChanges.builder()
                                        .title("File")
                                        .type("attachment")
                                        .status("inherit")
                                        .date(local("2027-01-01 00:00:00")))
                        .status())
                .isEqualTo("inherit");
        PostSnapshot now = create(editor, PostChanges.builder().title("Now").status("publish"));
        assertThat(now.publishedAt()).contains(NOW);
        assertThat(now.status()).isEqualTo("publish");
    }

    @Test
    void datesFloatForDraftsAndAreResolvedOtherwise() throws Exception {
        PostSnapshot dated = create(editor, PostChanges.builder().title("Dated").date(local("2026-03-01 09:00:00")));
        assertThat(dated.publishedAt()).isEmpty();
        assertThat(dated.localDate(PARIS)).isEqualTo(local("2026-03-01 09:00:00"));
        assertThat(dated.modifiedAt()).isEqualTo(Instant.parse("2026-03-01T08:00:00Z"));
        PostSnapshot both = create(
                editor,
                PostChanges.builder()
                        .title("Both")
                        .date(local("2026-03-01 09:00:00"))
                        .dateGmt(Instant.parse("2026-03-01T08:00:00Z")));
        assertThat(both.publishedAt()).contains(Instant.parse("2026-03-01T08:00:00Z"));
        PostSnapshot summer = create(
                editor, PostChanges.builder().title("Summer").status("private").date(local("2026-07-01 12:00:00")));
        assertThat(summer.publishedAt()).contains(Instant.parse("2026-07-01T10:00:00Z"));
        PostSnapshot old = create(
                editor, PostChanges.builder().title("Old").status("publish").date(local("2020-05-05 10:00:00")));
        assertThat(old.modifiedAt()).isEqualTo(Instant.parse("2020-05-05T08:00:00Z"));
    }

    @Test
    void aPrivatePostLosesItsPassword() throws Exception {
        PostSnapshot notes = create(
                editor, PostChanges.builder().title("Notes").status("private").password("x"));
        assertThat(notes.password()).isEmpty();
        assertThat(notes.slug()).isEqualTo("notes");
        assertThat(create(editor, PostChanges.builder().title("Locked").password(" x "))
                        .password())
                .isEqualTo(" x ");
    }

    @Test
    void pendingPostsOfUsersWhoCannotPublishLoseTheirSlug() throws Exception {
        UserId contributor = TestData.memberOfMain(dataSource, "contributor");
        assertThat(create(
                                contributor,
                                PostChanges.builder()
                                        .title("Mine")
                                        .status("pending")
                                        .slug("my-slug"))
                        .slug())
                .isEmpty();
        assertThat(create(
                                editor,
                                PostChanges.builder()
                                        .title("Ours")
                                        .status("pending")
                                        .slug("my-slug"))
                        .slug())
                .isEqualTo("my-slug");
        assertThat(create(
                                editor,
                                PostChanges.builder()
                                        .title("Ours")
                                        .status("pending")
                                        .slug("my-slug"))
                        .slug())
                .isEqualTo("my-slug");
    }

    @Test
    void anUnregisteredStatusIsStoredWithSlugAndDate() throws Exception {
        PostSnapshot post =
                create(editor, PostChanges.builder().title("Approved one").status("approved"));
        assertThat(post.status()).isEqualTo("approved");
        assertThat(post.slug()).isEqualTo("approved-one");
        assertThat(post.publishedAt()).contains(NOW);
    }

    @Test
    void typeAndStatusAreNormalized() throws Exception {
        assertThat(create(editor, PostChanges.builder().title("N").status("Publish"))
                        .status())
                .isEqualTo("publish");
        assertThat(create(editor, PostChanges.builder().title("N").type("Page "))
                        .type())
                .isEqualTo("page");
        assertThat(create(
                                editor,
                                PostChanges.builder()
                                        .title("N")
                                        .type("attachment")
                                        .status("publish"))
                        .status())
                .isEqualTo("inherit");
    }

    @Test
    void aValueTooLongForItsColumnIsRefusedAndNothingIsStored() throws Exception {
        assertThatThrownBy(
                        () -> create(editor, PostChanges.builder().title("Long").type("a_type_name_longer_than_20")))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("db_insert_error"));
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post where site_id = ?", site.value()))
                .isZero();
    }

    @Test
    void multibyteTitlesGiveWordPressSlugsOnBothDatabases() throws Exception {
        String party = cp(0x1F389) + " Party";
        PostSnapshot emoji = create(editor, PostChanges.builder().title(party).status("publish"));
        assertThat(emoji.slug()).isEqualTo("%f0%9f%8e%89-party");
        assertThat(emoji.title()).isEqualTo(party);
        assertThat(create(
                                editor,
                                PostChanges.builder()
                                        .title("Caf" + cp(0xE9) + " cr" + cp(0xE8) + "me")
                                        .status("publish"))
                        .slug())
                .isEqualTo("cafe-creme");
        PostSnapshot long1 = create(
                editor, PostChanges.builder().title(cp(0x65E5).repeat(80)).status("publish"));
        assertThat(long1.slug()).isEqualTo("%e6%97%a5".repeat(22));
        PostSnapshot long2 = create(
                editor, PostChanges.builder().title(cp(0x65E5).repeat(80)).status("publish"));
        assertThat(long2.slug()).isEqualTo("%e6%97%a5".repeat(22) + "-2");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select title from qp_post where id = ?",
                        emoji.id().value()))
                .containsExactly(party);
    }

    @Test
    void aLocalDateInTheSpringGapIsMovedForward() throws Exception {
        site = TestData.siteIn(dataSource, "America/New_York");
        PostSnapshot post = create(
                editor, PostChanges.builder().title("Gap").status("publish").date(local("2024-03-10 02:30:00")));
        assertThat(post.publishedAt()).contains(Instant.parse("2024-03-10T07:30:00Z"));
        PostSnapshot overlap = create(
                editor, PostChanges.builder().title("Overlap").status("publish").date(local("2024-11-03 01:30:00")));
        assertThat(overlap.publishedAt()).contains(Instant.parse("2024-11-03T05:30:00Z"));
    }

    @Test
    void aParentOfAnotherSiteIsRefused() throws Exception {
        SiteId other = TestData.siteIn(dataSource, "UTC");
        PostId foreign = TestData.post(dataSource, other, "page", "publish", "foreign", "Foreign");
        assertThatThrownBy(() -> create(
                        editor,
                        PostChanges.builder().title("Child").type("page").parent(foreign)))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_parent"));
        assertThatThrownBy(() ->
                        create(editor, PostChanges.builder().title("Orphan").parent(Long.MAX_VALUE)))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_parent"));
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post where site_id = ?", site.value()))
                .isZero();
        PostSnapshot page = create(editor, PostChanges.builder().title("Parent").type("page"));
        PostSnapshot child =
                create(editor, PostChanges.builder().title("Child").type("page").parent(page.id()));
        assertThat(child.parent()).contains(page.id());
        PostSnapshot attached =
                create(editor, PostChanges.builder().title("Post").parent(page.id()));
        assertThat(attached.parent()).contains(page.id());
    }

    @Test
    void findNeedsReadPostForNonPublicPosts() throws Exception {
        UserId subscriber = TestData.memberOfMain(dataSource, "subscriber");
        PostSnapshot secret =
                create(editor, PostChanges.builder().title("Secret").status("private"));
        PostSnapshot open = create(editor, PostChanges.builder().title("Open").status("publish"));
        assertThatThrownBy(() -> Identities.onSite(site, Identities.user(subscriber), () -> posts.find(secret.id())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability read_post on site " + site.value());
        assertThat(Identities.onSite(site, Identities.anonymous(), () -> posts.find(open.id())))
                .isPresent();
        assertThat(Identities.onSite(site, Identities.anonymous(), () -> posts.find(PostId.of(Long.MAX_VALUE))))
                .isEmpty();
    }

    @Test
    void contentIsFilteredForAuthorsButNotForEditorsOfASingleSite() throws Exception {
        UserId author = TestData.memberOfMain(dataSource, "author");
        PostSnapshot byAuthor = create(
                author,
                PostChanges.builder()
                        .title("<b>T</b> & co")
                        .content("<p onclick=\"x()\">Hi</p>")
                        .excerpt("<script>x</script>e"));
        assertThat(byAuthor.title()).isEqualTo("<b>T</b> &amp; co");
        assertThat(byAuthor.content()).isEqualTo("<p>Hi</p>");
        assertThat(byAuthor.excerpt()).isEqualTo("xe");
        PostSnapshot byEditor =
                create(editor, PostChanges.builder().title("  <b>T</b> & co  ").content("<p onclick=\"x()\">Hi</p>"));
        assertThat(byEditor.title()).isEqualTo("<b>T</b> & co");
        assertThat(byEditor.content()).isEqualTo("<p onclick=\"x()\">Hi</p>");
    }

    @Test
    void theSystemSavesFilteredContent() throws Exception {
        PostSnapshot post = asSystem(PostChanges.builder().title("Cron").content("<p onclick=\"x()\">Hi</p>"));
        assertThat(post.content()).isEqualTo("<p>Hi</p>");
    }

    @Test
    void aRefusedFindLeavesTheCallersTransactionUsable() throws Exception {
        UserId subscriber = TestData.memberOfMain(dataSource, "subscriber");
        PostSnapshot secret =
                create(editor, PostChanges.builder().title("Secret").status("private"));
        int status = Identities.onSite(site, Identities.user(subscriber), () -> {
            assertThatThrownBy(() -> posts.find(secret.id()))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("the caller lacks the capability read_post on site " + site.value());
            return transactions.getStatus();
        });
        assertThat(status).isEqualTo(Status.STATUS_ACTIVE);
    }

    @Test
    void pendingPostsOfAnUnregisteredTypeKeepTheirSlug() throws Exception {
        UserId contributor = TestData.memberOfMain(dataSource, "contributor");
        PostSnapshot thing = create(
                contributor,
                PostChanges.builder()
                        .title("Thing")
                        .type("thing")
                        .status("pending")
                        .slug("my-thing"));
        assertThat(thing.slug()).isEqualTo("my-thing");
        assertThat(create(
                                contributor,
                                PostChanges.builder()
                                        .title("Post")
                                        .status("pending")
                                        .slug("my-post"))
                        .slug())
                .isEmpty();
    }

    @Test
    void anAuthorWhoseProfileASpawningTransactionHoldsIsRefusedAtOnce() throws Exception {
        UserId author = TestData.memberOfMain(dataSource, "author");
        SiteId other = TestData.site(dataSource);
        assertThatThrownBy(() -> Identities.onSite(other, Identities.system(), () -> {
                    HeldLocks.record("qp_user_profile:" + author.value());
                    return create(editor, PostChanges.builder().title("Held").author(author));
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the profile of user " + author.value()
                        + " is locked by a transaction that waits for this post write: write the post in a separate"
                        + " transaction");
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post where site_id = ?", site.value()))
                .isZero();
        assertThat(create(editor, PostChanges.builder().title("Free").author(author))
                        .author())
                .contains(author);
    }

    @Test
    void theCallersOwnProfileHeldByASpawningTransactionIsRefusedAtOnce() throws Exception {
        SiteId other = TestData.site(dataSource);
        assertThatThrownBy(() -> Identities.onSite(other, Identities.system(), () -> {
                    HeldLocks.record("qp_user_profile:" + editor.value());
                    return create(editor, PostChanges.builder().title("Held"));
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the profile of user " + editor.value()
                        + " is locked by a transaction that waits for this post write: write the post in a separate"
                        + " transaction");
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post where site_id = ?", site.value()))
                .isZero();
    }
}
