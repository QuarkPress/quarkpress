package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.OrderKey;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostPage;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;

class QueryDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(QueryDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    SiteId site;
    UserId editor;
    UserId author;

    @BeforeEach
    void setUp() {
        site = TestData.site(dataSource);
        editor = TestData.memberOfMain(dataSource, "editor");
        author = TestData.memberOfMain(dataSource, "author");
    }

    /** A post created by the system, dated {@code day} days after 2020-01-01 (later days are newer). */
    private PostSnapshot post(PostChanges.Builder changes, int day) throws Exception {
        return postOn(site, changes, day);
    }

    private PostSnapshot postOn(SiteId on, PostChanges.Builder changes, int day) throws Exception {
        PostChanges built = changes.dateGmt(
                        Instant.parse("2020-01-01T00:00:00Z").plusSeconds(86_400L * day))
                .build();
        return Identities.onSite(on, Identities.system(), () -> posts.create(built));
    }

    private PostPage query(SecurityIdentity identity, PostQuery.Builder query) throws Exception {
        return Identities.onSite(site, identity, () -> posts.query(query.build()));
    }

    private List<PostId> ids(SecurityIdentity identity, PostQuery.Builder query) throws Exception {
        return ids(query(identity, query));
    }

    private static List<PostId> ids(PostPage page) {
        return page.posts().stream().map(PostSnapshot::id).toList();
    }

    private String lacks(String capability) {
        return "the caller lacks the capability " + capability + " on site " + site.value();
    }

    @Test
    void theDefaultIsPublishedPostsNewestFirstTenAPage() throws Exception {
        List<PostId> published = new ArrayList<>();
        for (int day = 1; day <= 12; day++) {
            published.add(post(PostChanges.builder().title("P" + day).status("publish"), day)
                    .id());
        }
        post(PostChanges.builder().title("Draft").status("draft"), 20);
        post(PostChanges.builder().title("Page").type("page").status("publish"), 21);
        post(PostChanges.builder().title("Private").status("private").author(editor), 22);
        PostPage first = query(Identities.anonymous(), PostQuery.builder());
        assertThat(ids(first)).containsExactlyElementsOf(published.reversed().subList(0, 10));
        assertThat(first.found()).isEqualTo(12);
        assertThat(first.maxPages()).isEqualTo(2);
        assertThat(ids(Identities.anonymous(), PostQuery.builder().page(2)))
                .containsExactly(published.get(1), published.get(0));
    }

    @Test
    void privatePostsShowForUsersWhoMayReadThem() throws Exception {
        PostSnapshot mine =
                post(PostChanges.builder().title("Mine").status("private").author(author), 1);
        PostSnapshot theirs =
                post(PostChanges.builder().title("Theirs").status("private").author(editor), 2);
        PostSnapshot open = post(PostChanges.builder().title("Open").status("publish"), 3);
        assertThat(ids(Identities.anonymous(), PostQuery.builder())).containsExactly(open.id());
        assertThat(ids(Identities.system(), PostQuery.builder())).containsExactly(open.id());
        assertThat(ids(Identities.user(author), PostQuery.builder())).containsExactly(open.id(), mine.id());
        assertThat(ids(Identities.user(editor), PostQuery.builder()))
                .containsExactly(open.id(), theirs.id(), mine.id());
    }

    @Test
    void explicitStatusesFollowWordPressIncludingItsQuirks() throws Exception {
        PostSnapshot draft = post(PostChanges.builder().title("Draft"), 1);
        PostSnapshot trashed = post(PostChanges.builder().title("Trashed").status("trash"), 2);
        PostSnapshot auto = post(PostChanges.builder().title("Auto").status("auto-draft"), 3);
        PostSnapshot published = post(PostChanges.builder().title("Published").status("publish"), 4);
        SecurityIdentity as = Identities.user(editor);
        assertThat(ids(as, PostQuery.builder().statuses("draft", "bogus"))).containsExactly(draft.id());
        assertThat(ids(as, PostQuery.builder().statuses("bogus")))
                .containsExactly(published.id(), auto.id(), trashed.id(), draft.id());
        assertThat(ids(as, PostQuery.builder().statuses("any"))).containsExactly(published.id(), draft.id());
        assertThat(ids(as, PostQuery.builder().statuses("any", "trash")))
                .containsExactly(published.id(), trashed.id(), draft.id());
        assertThat(ids(as, PostQuery.builder().statuses(" Draft "))).containsExactly(draft.id());
    }

    @Test
    void explicitStatusesShowOthersPostsOnlyToThoseWhoMayReadThem() throws Exception {
        PostSnapshot editorsPrivate =
                post(PostChanges.builder().title("EP").status("private").author(editor), 1);
        PostSnapshot authorsPrivate =
                post(PostChanges.builder().title("AP").status("private").author(author), 2);
        PostSnapshot editorsDraft =
                post(PostChanges.builder().title("ED").status("draft").author(editor), 3);
        PostSnapshot authorsDraft =
                post(PostChanges.builder().title("AD").status("draft").author(author), 4);
        PostSnapshot published =
                post(PostChanges.builder().title("P").status("publish").author(editor), 5);
        SecurityIdentity asAuthor = Identities.user(author);
        SecurityIdentity asEditor = Identities.user(editor);
        assertThat(ids(asAuthor, PostQuery.builder().statuses("private"))).containsExactly(authorsPrivate.id());
        assertThat(ids(asEditor, PostQuery.builder().statuses("private")))
                .containsExactly(authorsPrivate.id(), editorsPrivate.id());
        assertThat(ids(asAuthor, PostQuery.builder().statuses("draft"))).containsExactly(authorsDraft.id());
        assertThat(ids(asEditor, PostQuery.builder().statuses("draft")))
                .containsExactly(authorsDraft.id(), editorsDraft.id());
        assertThat(ids(asAuthor, PostQuery.builder().statuses("any")))
                .containsExactly(published.id(), authorsDraft.id(), authorsPrivate.id());
        assertThat(ids(asAuthor, PostQuery.builder().statuses("bogus")))
                .containsExactly(published.id(), authorsDraft.id(), authorsPrivate.id());
        assertThat(ids(asAuthor, PostQuery.builder().statuses("publish", "private")))
                .containsExactly(published.id(), authorsPrivate.id());
        assertThat(ids(Identities.system(), PostQuery.builder().statuses("private", "draft")))
                .containsExactly(authorsDraft.id(), editorsDraft.id(), authorsPrivate.id(), editorsPrivate.id());
    }

    @Test
    void askingForStatusesBeyondPublishNeedsEditPosts() throws Exception {
        UserId subscriber = TestData.memberOfMain(dataSource, "subscriber");
        PostSnapshot published = post(PostChanges.builder().title("P").status("publish"), 1);
        assertThatThrownBy(() ->
                        query(Identities.user(subscriber), PostQuery.builder().statuses("draft")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_posts"));
        assertThatThrownBy(
                        () -> query(Identities.anonymous(), PostQuery.builder().statuses("any")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(lacks("edit_posts"));
        assertThat(ids(Identities.user(subscriber), PostQuery.builder().statuses("publish")))
                .containsExactly(published.id());
        assertThatThrownBy(() -> query(
                        Identities.user(author),
                        PostQuery.builder().types("page").statuses("draft")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_pages"));
    }

    @Test
    void typesAreChosenExplicitlyOrByAny() throws Exception {
        PostSnapshot post = post(PostChanges.builder().title("Post").status("publish"), 1);
        PostSnapshot page =
                post(PostChanges.builder().title("Page").type("page").status("publish"), 2);
        post(PostChanges.builder().title("Block").type("wp_block").status("publish"), 3);
        assertThat(ids(Identities.anonymous(), PostQuery.builder().types("page")))
                .containsExactly(page.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().types("post", "page")))
                .containsExactly(page.id(), post.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().types("any")))
                .containsExactly(page.id(), post.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().types(""))).isEmpty();
        assertThat(ids(Identities.anonymous(), PostQuery.builder().types(" ANY ")))
                .containsExactly(page.id(), post.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().types("post", "any")))
                .containsExactly(post.id());
    }

    @Test
    void authorFiltersFollowWordPress() throws Exception {
        PostSnapshot byAuthor =
                post(PostChanges.builder().title("A").status("publish").author(author), 1);
        PostSnapshot byEditor =
                post(PostChanges.builder().title("E").status("publish").author(editor), 2);
        PostSnapshot byNobody = post(PostChanges.builder().title("N").status("publish"), 3);
        assertThat(ids(Identities.anonymous(), PostQuery.builder().author(author)))
                .containsExactly(byAuthor.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().authorIn(author, editor)))
                .containsExactly(byEditor.id(), byAuthor.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().authorNotIn(editor)))
                .containsExactly(byNobody.id(), byAuthor.id());
        assertThat(ids(
                        Identities.anonymous(),
                        PostQuery.builder().authorIn(author).authorNotIn(editor)))
                .containsExactly(byNobody.id(), byAuthor.id());
    }

    @Test
    void parentAndIdFiltersFollowWordPress() throws Exception {
        PostSnapshot top = post(PostChanges.builder().title("Top").type("page").status("publish"), 1);
        PostSnapshot child = post(
                PostChanges.builder()
                        .title("Child")
                        .type("page")
                        .status("publish")
                        .parent(top.id()),
                2);
        assertThat(ids(Identities.anonymous(), PostQuery.builder().types("page").parent(0)))
                .containsExactly(top.id());
        assertThat(ids(
                        Identities.anonymous(),
                        PostQuery.builder().types("page").parent(top.id().value())))
                .containsExactly(child.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().types("page").parentIn(top.id())))
                .containsExactly(child.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().types("page").parentNotIn(top.id())))
                .containsExactly(top.id());
        assertThat(ids(
                        Identities.anonymous(),
                        PostQuery.builder()
                                .types("page")
                                .postIn(top.id(), child.id())
                                .postNotIn(child.id())))
                .containsExactly(child.id(), top.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().types("page").postNotIn(child.id())))
                .containsExactly(top.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().types("page").id(child.id())))
                .containsExactly(child.id());
    }

    @Test
    void slugsAreMatchedInTheQueryContextAndTitlesIgnoringCase() throws Exception {
        PostSnapshot accented =
                post(PostChanges.builder().title("x").slug("caf%c3%a9").status("publish"), 1);
        PostSnapshot plain = post(PostChanges.builder().title("Cafe").status("publish"), 2);
        PostSnapshot titled = post(
                PostChanges.builder().title("Exact Title").status("publish").menuOrder(3), 3);
        assertThat(ids(Identities.anonymous(), PostQuery.builder().name("Caf" + (char) 0xE9)))
                .containsExactly(accented.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().name("cafe")))
                .containsExactly(plain.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().title("Exact Title")))
                .containsExactly(titled.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().title("exact TITLE")))
                .containsExactly(titled.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().title("Exact")))
                .isEmpty();
        assertThat(ids(Identities.anonymous(), PostQuery.builder().menuOrder(3)))
                .containsExactly(titled.id());
    }

    @Test
    void aSinglePostQueryShowsOnlyWhatTheCallerMayRead() throws Exception {
        PostSnapshot draft = post(PostChanges.builder().title("Hidden draft").slug("hidden"), 1);
        PostSnapshot published =
                post(PostChanges.builder().title("Open").slug("open").status("publish"), 2);
        assertThat(ids(Identities.anonymous(), PostQuery.builder().id(draft.id())))
                .isEmpty();
        assertThat(ids(Identities.anonymous(), PostQuery.builder().name("hidden")))
                .isEmpty();
        assertThat(ids(Identities.system(), PostQuery.builder().id(draft.id()))).isEmpty();
        assertThat(ids(Identities.user(author), PostQuery.builder().id(draft.id())))
                .isEmpty();
        assertThat(ids(Identities.user(editor), PostQuery.builder().id(draft.id())))
                .containsExactly(draft.id());
        assertThat(ids(Identities.user(editor), PostQuery.builder().name("hidden")))
                .containsExactly(draft.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().id(published.id())))
                .containsExactly(published.id());
        PostPage unpaged =
                query(Identities.anonymous(), PostQuery.builder().name("open").page(3));
        assertThat(ids(unpaged)).containsExactly(published.id());
        assertThat(unpaged.found()).isEqualTo(1);
        assertThat(unpaged.maxPages()).isZero();
    }

    @Test
    void queriesSeeOnlyTheCurrentSite() throws Exception {
        SiteId other = TestData.site(dataSource);
        PostSnapshot elsewhere =
                postOn(other, PostChanges.builder().title("Elsewhere").status("publish"), 1);
        PostSnapshot here = post(PostChanges.builder().title("Here").status("publish"), 2);
        assertThat(ids(Identities.anonymous(), PostQuery.builder().id(elsewhere.id())))
                .isEmpty();
        assertThat(ids(Identities.user(editor), PostQuery.builder().statuses("any")))
                .containsExactly(here.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().postIn(elsewhere.id(), here.id())))
                .containsExactly(here.id());
    }

    @Test
    void orderingFollowsTheGivenKeysAndBreaksTiesById() throws Exception {
        PostSnapshot b =
                post(PostChanges.builder().title("beta").status("publish").menuOrder(1), 1);
        PostSnapshot a =
                post(PostChanges.builder().title("Alpha").status("publish").menuOrder(2), 2);
        PostSnapshot c =
                post(PostChanges.builder().title("gamma").status("publish").menuOrder(1), 3);
        assertThat(ids(Identities.anonymous(), PostQuery.builder().orderBy(OrderKey.TITLE, true)))
                .containsExactly(a.id(), b.id(), c.id());
        assertThat(ids(
                        Identities.anonymous(),
                        PostQuery.builder().orderBy(OrderKey.MENU_ORDER, true).orderBy(OrderKey.DATE, false)))
                .containsExactly(c.id(), b.id(), a.id());
        assertThat(ids(
                        Identities.anonymous(),
                        PostQuery.builder().postIn(c.id(), a.id(), b.id()).orderBy(OrderKey.POST_IN, true)))
                .containsExactly(c.id(), a.id(), b.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().orderBy(OrderKey.DATE, true)))
                .containsExactly(b.id(), a.id(), c.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().orderBy(OrderKey.MENU_ORDER, false)))
                .containsExactly(a.id(), c.id(), b.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().orderBy(OrderKey.ID, true)))
                .containsExactly(b.id(), a.id(), c.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().orderBy(OrderKey.NONE, true)))
                .containsExactlyInAnyOrder(a.id(), b.id(), c.id());
    }

    @Test
    void keysThatDoNotApplyAreSkippedAndNoneLeftMeansNewestFirst() throws Exception {
        PostSnapshot b = post(PostChanges.builder().title("beta").status("publish"), 1);
        PostSnapshot a = post(PostChanges.builder().title("Alpha").status("publish"), 2);
        PostSnapshot c = post(PostChanges.builder().title("gamma").status("publish"), 3);
        assertThat(ids(Identities.anonymous(), PostQuery.builder().orderBy(OrderKey.RELEVANCE, true)))
                .containsExactly(c.id(), a.id(), b.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().orderBy(OrderKey.POST_IN, true)))
                .containsExactly(c.id(), a.id(), b.id());
        assertThat(ids(
                        Identities.anonymous(),
                        PostQuery.builder().orderBy(OrderKey.RELEVANCE, false).orderBy(OrderKey.TITLE, true)))
                .containsExactly(a.id(), b.id(), c.id());
    }

    @Test
    void pagingFollowsWordPress() throws Exception {
        List<PostId> made = new ArrayList<>();
        for (int day = 1; day <= 25; day++) {
            made.add(post(PostChanges.builder().title("P" + day).status("publish"), day)
                    .id());
        }
        List<PostId> newest = made.reversed();
        PostPage third =
                query(Identities.anonymous(), PostQuery.builder().perPage(10).page(3));
        assertThat(ids(third)).containsExactlyElementsOf(newest.subList(20, 25));
        assertThat(third.found()).isEqualTo(25);
        assertThat(third.maxPages()).isEqualTo(3);
        assertThat(ids(
                        Identities.anonymous(),
                        PostQuery.builder().perPage(10).offset(5).page(3)))
                .containsExactlyElementsOf(newest.subList(5, 15));
        assertThat(ids(Identities.anonymous(), PostQuery.builder().perPage(10).offset(-5)))
                .containsExactlyElementsOf(newest.subList(5, 15));
        assertThat(ids(Identities.anonymous(), PostQuery.builder().perPage(10).page(-2)))
                .containsExactlyElementsOf(newest.subList(10, 20));
        assertThat(ids(Identities.anonymous(), PostQuery.builder().perPage(10).page(0)))
                .containsExactlyElementsOf(newest.subList(0, 10));
        assertThat(ids(Identities.anonymous(), PostQuery.builder().perPage(0)))
                .containsExactlyElementsOf(newest.subList(0, 10));
        assertThat(ids(Identities.anonymous(), PostQuery.builder().perPage(-5)))
                .containsExactlyElementsOf(newest.subList(0, 5));
        PostPage all =
                query(Identities.anonymous(), PostQuery.builder().perPage(-1).offset(5));
        assertThat(ids(all)).containsExactlyElementsOf(newest);
        assertThat(all.found()).isEqualTo(25);
        assertThat(all.maxPages()).isZero();
        PostPage beyond =
                query(Identities.anonymous(), PostQuery.builder().perPage(10).page(9));
        assertThat(beyond.posts()).isEmpty();
        assertThat(beyond.found()).isZero();
        assertThat(beyond.maxPages()).isZero();
    }

    @Test
    void theSitesPostsPerPageOptionIsTheDefault() throws Exception {
        TestData.siteOption(dataSource, site, "posts_per_page", "3");
        List<PostId> made = new ArrayList<>();
        for (int day = 1; day <= 4; day++) {
            made.add(post(PostChanges.builder().title("P" + day).status("publish"), day)
                    .id());
        }
        List<PostId> newest = made.reversed();
        assertThat(ids(Identities.anonymous(), PostQuery.builder())).containsExactlyElementsOf(newest.subList(0, 3));
        assertThat(ids(Identities.anonymous(), PostQuery.builder().perPage(0)))
                .containsExactlyElementsOf(newest.subList(0, 3));
        TestData.siteOption(dataSource, site, "posts_per_page", "0");
        assertThat(ids(Identities.anonymous(), PostQuery.builder())).containsExactly(newest.get(0));
    }

    @Test
    void aSinglePostIsCheckedAsWordPressChecksIt() throws Exception {
        UserId subscriber = TestData.memberOfMain(dataSource, "subscriber");
        PostSnapshot trashed =
                post(PostChanges.builder().title("T").status("trash").author(editor), 1);
        PostSnapshot auto =
                post(PostChanges.builder().title("AD").status("auto-draft").author(editor), 2);
        PostSnapshot editorsPrivate =
                post(PostChanges.builder().title("EP").status("private").author(editor), 3);
        PostSnapshot authorsPrivate =
                post(PostChanges.builder().title("AP").status("private").author(author), 4);
        PostSnapshot draft = post(PostChanges.builder().title("D"), 5);
        PostSnapshot subscribersDraft = post(PostChanges.builder().title("SD").author(subscriber), 6);
        SecurityIdentity asEditor = Identities.user(editor);
        assertThat(ids(asEditor, PostQuery.builder().id(trashed.id()))).isEmpty();
        assertThat(ids(asEditor, PostQuery.builder().id(trashed.id()).statuses("trash")))
                .containsExactly(trashed.id());
        assertThat(ids(asEditor, PostQuery.builder().id(trashed.id()).statuses("bogus")))
                .isEmpty();
        assertThat(ids(asEditor, PostQuery.builder().id(auto.id()))).isEmpty();
        assertThat(ids(Identities.user(author), PostQuery.builder().id(editorsPrivate.id())))
                .isEmpty();
        assertThat(ids(Identities.user(author), PostQuery.builder().id(authorsPrivate.id())))
                .containsExactly(authorsPrivate.id());
        assertThat(ids(asEditor, PostQuery.builder().id(authorsPrivate.id()))).containsExactly(authorsPrivate.id());
        assertThat(ids(Identities.system(), PostQuery.builder().id(draft.id()).statuses("any")))
                .isEmpty();
        assertThat(ids(Identities.system(), PostQuery.builder().id(draft.id()).statuses("draft")))
                .containsExactly(draft.id());
        assertThat(ids(Identities.user(subscriber), PostQuery.builder().id(subscribersDraft.id())))
                .isEmpty();
        PostPage hidden = query(asEditor, PostQuery.builder().id(trashed.id()));
        assertThat(hidden.found()).isZero();
        assertThat(hidden.maxPages()).isZero();
    }

    @Test
    void inheritingPostsAreReadThroughTheirParent() throws Exception {
        PostSnapshot published =
                post(PostChanges.builder().title("Pub").status("publish").author(editor), 1);
        PostSnapshot draft = post(PostChanges.builder().title("Draft").author(editor), 2);
        PostSnapshot onPublished = post(
                PostChanges.builder()
                        .title("F1")
                        .type("attachment")
                        .status("inherit")
                        .parent(published.id())
                        .author(editor),
                3);
        PostSnapshot onDraft = post(
                PostChanges.builder()
                        .title("F2")
                        .type("attachment")
                        .status("inherit")
                        .parent(draft.id())
                        .author(editor),
                4);
        PostSnapshot loose = post(
                PostChanges.builder()
                        .title("F3")
                        .type("attachment")
                        .status("inherit")
                        .author(editor),
                5);
        PostQuery.Builder inherited = PostQuery.builder().types("attachment").statuses("inherit");
        assertThat(ids(Identities.user(author), inherited)).containsExactly(loose.id(), onPublished.id());
        assertThat(ids(Identities.user(editor), inherited)).containsExactly(loose.id(), onDraft.id(), onPublished.id());
    }

    @Test
    void inheritingPostsAreAlsoReadThroughThemselves() throws Exception {
        UserId othersEditor = TestData.profile(dataSource, "others-editor");
        Jdbc.update(
                dataSource,
                "insert into qp_site_membership (site_id, user_id, roles, capabilities) values (?, ?, " + TestDb.json()
                        + ", " + TestDb.json() + ")",
                TestData.MAIN.value(),
                othersEditor.value(),
                "[\"author\"]",
                "{\"edit_others_posts\": true}");
        PostSnapshot draft = post(PostChanges.builder().title("D").author(editor), 1);
        PostSnapshot hidden =
                post(PostChanges.builder().title("P").status("private").author(editor), 2);
        PostSnapshot draftPage =
                post(PostChanges.builder().title("DP").type("page").author(editor), 3);
        PostSnapshot privatePage = post(
                PostChanges.builder().title("PP").type("page").status("private").author(editor), 4);
        PostSnapshot ownUnderDraft = attachment(draft, author, 5);
        PostSnapshot ownUnderPrivate = attachment(hidden, author, 6);
        PostSnapshot underDraftPage = attachment(draftPage, editor, 7);
        attachment(privatePage, editor, 8);
        PostQuery.Builder inherited = PostQuery.builder().types("attachment").statuses("inherit");
        assertThat(ids(Identities.user(author), inherited)).containsExactly(ownUnderPrivate.id(), ownUnderDraft.id());
        assertThat(ids(Identities.user(othersEditor), inherited))
                .containsExactly(underDraftPage.id(), ownUnderDraft.id());
    }

    private PostSnapshot attachment(PostSnapshot parent, UserId by, int day) throws Exception {
        return post(
                PostChanges.builder()
                        .title("F" + day)
                        .type("attachment")
                        .status("inherit")
                        .parent(parent.id())
                        .author(by),
                day);
    }

    @Test
    void ownPostsOfUnregisteredStatusesNeedEditOthersPosts() throws Exception {
        PostSnapshot published = post(PostChanges.builder().title("P").status("publish"), 1);
        PostId odd = TestData.post(dataSource, site, "post", "bogus-status", "odd", "Odd");
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", author.value(), odd.value());
        assertThat(ids(Identities.user(author), PostQuery.builder().statuses("any")))
                .containsExactly(published.id());
        assertThat(ids(Identities.user(editor), PostQuery.builder().statuses("any")))
                .containsExactly(odd, published.id());
    }

    @Test
    void readabilityIsDecidedPerType() throws Exception {
        UserId pageEditor = TestData.profile(dataSource, "page-editor");
        Jdbc.update(
                dataSource,
                "insert into qp_site_membership (site_id, user_id, roles, capabilities) values (?, ?, " + TestDb.json()
                        + ", " + TestDb.json() + ")",
                TestData.MAIN.value(),
                pageEditor.value(),
                "[\"author\"]",
                "{\"edit_pages\": true, \"edit_others_pages\": true, \"read_private_pages\": true}");
        PostSnapshot editorsPost = post(PostChanges.builder().title("EPo").author(editor), 1);
        PostSnapshot editorsPage =
                post(PostChanges.builder().title("EPa").type("page").author(editor), 2);
        PostSnapshot ownPost = post(PostChanges.builder().title("OPo").author(pageEditor), 3);
        PostSnapshot ownPage =
                post(PostChanges.builder().title("OPa").type("page").author(pageEditor), 4);
        PostSnapshot editorsPrivatePage = post(
                PostChanges.builder()
                        .title("PPa")
                        .type("page")
                        .status("private")
                        .author(editor),
                5);
        PostSnapshot editorsPrivatePost =
                post(PostChanges.builder().title("PPo").status("private").author(editor), 6);
        assertThat(ids(
                        Identities.user(pageEditor),
                        PostQuery.builder().types("post", "page").statuses("draft", "private")))
                .containsExactly(editorsPrivatePage.id(), ownPage.id(), ownPost.id(), editorsPage.id());
        assertThat(ids(
                        Identities.user(editor),
                        PostQuery.builder().types("post", "page").statuses("draft", "private")))
                .containsExactly(
                        editorsPrivatePost.id(),
                        editorsPrivatePage.id(),
                        ownPage.id(),
                        ownPost.id(),
                        editorsPage.id(),
                        editorsPost.id());
    }

    @Test
    void blankSlugsAndTitlesApplyNoConditionAndMenuOrderIsAbsolute() throws Exception {
        post(PostChanges.builder().title("Draft"), 1);
        PostSnapshot titled = post(
                PostChanges.builder().title("Exact Title").status("publish").menuOrder(3), 2);
        PostSnapshot other = post(PostChanges.builder().title("Other").status("publish"), 3);
        for (String blank : List.of("", "  \t")) {
            PostPage page = query(Identities.anonymous(), PostQuery.builder().name(blank));
            assertThat(ids(page)).containsExactly(other.id(), titled.id());
            assertThat(page.found()).isEqualTo(2);
            assertThat(page.maxPages()).isEqualTo(1);
            assertThat(ids(Identities.anonymous(), PostQuery.builder().title(blank)))
                    .containsExactly(other.id(), titled.id());
        }
        assertThat(ids(Identities.anonymous(), PostQuery.builder().title("\0 Exact Title\u000B\n")))
                .containsExactly(titled.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().name(" exact-title ")))
                .containsExactly(titled.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().menuOrder(-3)))
                .containsExactly(titled.id());
    }

    @Test
    void everyColumnKeyOrdersAsGiven() throws Exception {
        PostSnapshot x = post(
                PostChanges.builder()
                        .title("x")
                        .slug("b-slug")
                        .status("publish")
                        .author(author),
                1);
        PostSnapshot y = post(
                PostChanges.builder()
                        .title("y")
                        .slug("c-slug")
                        .status("publish")
                        .author(editor),
                2);
        PostSnapshot z = post(PostChanges.builder().title("z").slug("a-slug").status("publish"), 3);
        assertThat(ids(Identities.anonymous(), PostQuery.builder().orderBy(OrderKey.NAME, true)))
                .containsExactly(z.id(), x.id(), y.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().orderBy(OrderKey.NAME, false)))
                .containsExactly(y.id(), x.id(), z.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().orderBy(OrderKey.AUTHOR, true)))
                .containsExactly(z.id(), y.id(), x.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().orderBy(OrderKey.AUTHOR, false)))
                .containsExactly(x.id(), y.id(), z.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().orderBy(OrderKey.MODIFIED, true)))
                .containsExactly(x.id(), y.id(), z.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().orderBy(OrderKey.MODIFIED, false)))
                .containsExactly(z.id(), y.id(), x.id());
    }

    @Test
    void parentAndTypeKeysCountAMissingParentAsZero() throws Exception {
        PostSnapshot top = post(PostChanges.builder().title("Top").type("page").status("publish"), 1);
        PostSnapshot second =
                post(PostChanges.builder().title("Second").type("page").status("publish"), 2);
        PostSnapshot child = post(
                PostChanges.builder()
                        .title("Child")
                        .type("page")
                        .status("publish")
                        .parent(top.id()),
                3);
        PostSnapshot post = post(PostChanges.builder().title("Post").status("publish"), 4);
        assertThat(ids(Identities.anonymous(), PostQuery.builder().types("page").orderBy(OrderKey.PARENT, true)))
                .containsExactly(top.id(), second.id(), child.id());
        assertThat(ids(Identities.anonymous(), PostQuery.builder().types("page").orderBy(OrderKey.PARENT, false)))
                .containsExactly(child.id(), second.id(), top.id());
        assertThat(ids(
                        Identities.anonymous(),
                        PostQuery.builder().types("post", "page").orderBy(OrderKey.TYPE, true)))
                .containsExactly(top.id(), second.id(), child.id(), post.id());
        assertThat(ids(
                        Identities.anonymous(),
                        PostQuery.builder().types("post", "page").orderBy(OrderKey.TYPE, false)))
                .containsExactly(post.id(), child.id(), second.id(), top.id());
    }
}
