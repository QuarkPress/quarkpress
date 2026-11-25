package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkword.front.deployment.FrontCast.as;
import static org.quarkword.front.deployment.FrontCast.ids;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.front.FrontFlags;
import org.quarkword.front.FrontRequest;
import org.quarkword.front.Viewer;

/**
 * Visibility never leaks through a side door: a draft and a private post asked for by id, by slug, by their dated
 * path, through an attachment and through a search, under the default permalink structure; whoever may not see the
 * post gets the same 404 as for a post that does not exist.
 */
class SingularGateDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(SingularGateDbTest.class, FrontCast.class);

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    static FrontCast cast;
    static PostId privateShot;

    @BeforeEach
    void cast() {
        if (cast == null) {
            cast = new FrontCast(dataSource);
            TestData.textOption(dataSource, cast.site, "permalink_structure", "/%year%/%monthnum%/%day%/%postname%/");
            privateShot = TestData.post(
                    dataSource,
                    cast.site,
                    "attachment",
                    "inherit",
                    "private-shot",
                    cast.alice,
                    cast.p6,
                    "2026-04-15T08:30:00Z",
                    false);
        }
    }

    /** The ways to ask for one post: by id, by slug, by its dated path, and by the id of an attachment of it. */
    private List<FrontRequest> asked(String slug, String datedPath, long id, long attachment, Viewer viewer)
            throws Exception {
        return List.of(
                cast.get("p=" + id, viewer),
                cast.get("name=" + slug, viewer),
                cast.resolve(datedPath, Map.of(), viewer),
                cast.get("attachment_id=" + attachment, viewer));
    }

    private static void allNotFound(List<FrontRequest> requests) {
        for (FrontRequest request : requests) {
            assertThat(request.status()).as(request.vars().toString()).isEqualTo(404);
            assertThat(request.flags()).as(request.vars().toString()).isEqualTo(FrontFlags.notFound());
            assertThat(request.shownPost()).isEmpty();
            assertThat(request.posts().posts()).isEmpty();
        }
    }

    private static void allShown(List<FrontRequest> requests, boolean preview) {
        for (FrontRequest request : requests) {
            assertThat(request.status()).as(request.vars().toString()).isEqualTo(200);
            assertThat(request.flags().preview()).as(request.vars().toString()).isEqualTo(preview);
            assertThat(request.shownPost()).isPresent();
        }
    }

    @Test
    void aDraftIsHiddenFromEveryoneWhoMayNotEditIt() throws Exception {
        long draft = cast.p4.value();
        long shot = cast.draftShot32.value();
        String path = "/2026/02/01/travel-diary/";
        allNotFound(asked("travel-diary", path, draft, shot, Viewer.anonymous()));
        allNotFound(asked("travel-diary", path, draft, shot, as(cast.erin)));
        allShown(asked("travel-diary", path, draft, shot, as(cast.carol)), true);
        allShown(asked("travel-diary", path, draft, shot, as(cast.bob)), true);
        assertThat(ids(cast.get("s=travel", as(cast.carol)))).isEmpty();
        assertThat(ids(cast.get("s=travel"))).isEmpty();
    }

    @Test
    void aPrivatePostIsShownOnlyToItsReaders() throws Exception {
        long secret = cast.p6.value();
        long shot = privateShot.value();
        String path = "/2026/04/15/board-minutes/";
        allNotFound(asked("board-minutes", path, secret, shot, Viewer.anonymous()));
        allNotFound(asked("board-minutes", path, secret, shot, as(cast.erin)));
        allNotFound(asked("board-minutes", path, secret, shot, as(cast.carol)));
        allShown(asked("board-minutes", path, secret, shot, as(cast.bob)), false);
        allShown(asked("board-minutes", path, secret, shot, as(cast.alice)), false);
        assertThat(ids(cast.get("s=board"))).isEmpty();
        assertThat(ids(cast.get("s=board", as(cast.erin)))).isEmpty();
    }

    @Test
    void aScheduledPostNeedsEditPost() throws Exception {
        String path = "/2027/01/01/launch-day/";
        assertThat(cast.resolve(path, Map.of(), Viewer.anonymous()).status()).isEqualTo(404);
        assertThat(cast.resolve(path, Map.of(), as(cast.carol)).status()).isEqualTo(404);
        FrontRequest bob = cast.resolve(path, Map.of(), as(cast.bob));
        assertThat(bob.status()).isEqualTo(200);
        assertThat(bob.flags().preview()).isTrue();
    }

    @Test
    void aMissingPostIsTheSame404() throws Exception {
        FrontRequest missing = cast.resolve("/2026/02/01/no-such-post/", Map.of(), Viewer.anonymous());
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.flags()).isEqualTo(FrontFlags.notFound());
        FrontRequest hidden = cast.resolve("/2026/02/01/travel-diary/", Map.of(), Viewer.anonymous());
        assertThat(hidden.flags()).isEqualTo(missing.flags());
        assertThat(hidden.queriedObject()).isEqualTo(missing.queriedObject());
    }

    @Test
    void aHiddenPagesNotFoundCarriesNothingOfThePage() throws Exception {
        // the page path resolves to the draft page, which the viewer may not read: its 404 is a missing page's
        for (var viewer : List.of(Viewer.anonymous(), as(cast.erin))) {
            FrontRequest missing = cast.get("pagename=no-such-page", viewer);
            FrontRequest hidden = cast.get("pagename=hidden", viewer);
            assertThat(missing.status()).isEqualTo(404);
            assertThat(hidden.status()).isEqualTo(404);
            assertThat(hidden.flags()).isEqualTo(missing.flags());
            assertThat(hidden.queriedObject())
                    .isEqualTo(missing.queriedObject())
                    .isEmpty();
            assertThat(hidden.shownPost()).isEmpty();
            FrontRequest byPath = cast.resolve("/hidden/", Map.of(), viewer);
            assertThat(byPath.status()).isEqualTo(404);
            assertThat(byPath.queriedObject()).isEmpty();
        }
        // a published page keeps its queried object on a 404 (a content page it lacks), as in WordPress
        FrontRequest about = cast.get("pagename=about&page=3");
        assertThat(about.status()).isEqualTo(404);
        assertThat(about.queriedObject()).isPresent();
        // a private page too, for whoever may read it
        assertThat(cast.get("p=" + cast.p6.value() + "&page=3", as(cast.bob)).queriedObject())
                .isPresent();
        assertThat(cast.get("p=" + cast.p6.value() + "&page=3", as(cast.bob)).status())
                .isEqualTo(404);
        // whoever may edit the draft page sees it
        assertThat(cast.get("pagename=hidden", as(cast.alice)).status()).isEqualTo(200);
    }
}
