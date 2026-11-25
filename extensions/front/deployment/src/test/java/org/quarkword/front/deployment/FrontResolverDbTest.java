package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkword.front.deployment.FrontCast.as;
import static org.quarkword.front.deployment.FrontCast.ids;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteContext;
import org.quarkword.core.UserId;
import org.quarkword.front.FrontFlags;
import org.quarkword.front.FrontRequest;
import org.quarkword.front.QueriedObject;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.PreviewTokens;
import org.quarkword.revisions.AutosaveChanges;
import org.quarkword.revisions.Revisions;

/**
 * A request's variables resolved into WordPress's main query, its visibility gate, previews and the 404 decision,
 * over the query reference's cast with plain permalinks. Where WordPress's answers (front-facts.json and
 * main-query.out in the slice folder) differ from the reference's prose, the test follows WordPress.
 */
class FrontResolverDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(FrontResolverDbTest.class, FrontCast.class);

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    static FrontCast cast;

    @BeforeEach
    void cast() {
        if (cast == null) {
            cast = new FrontCast(dataSource);
        }
        cast.dropOption("posts_per_page");
        cast.option("show_on_front", "\"posts\"");
        cast.option("page_on_front", "0");
        cast.option("page_for_posts", "0");
    }

    private static long id(FrontRequest request) {
        return request.shownPost().orElseThrow().id().value();
    }

    @Test
    void aPublishedPostIsShown() throws Exception {
        FrontRequest post = cast.get("p=" + cast.p10.value());
        assertThat(post.status()).isEqualTo(200);
        assertThat(post.flags()).isEqualTo(FrontFlags.none().withSingle(true).withSingular(true));
        assertThat(id(post)).isEqualTo(cast.p10.value());
        assertThat(post.queriedObject()).containsInstanceOf(QueriedObject.Post.class);
        assertThat(post.previewArmed()).isFalse();
        assertThat(post.shownAt()).isEqualTo(Instant.parse("2026-07-10T08:00:00Z"));
    }

    @Test
    void aDraftIsAPreviewForItsAuthorAndNotFoundForOthers() throws Exception {
        FrontRequest anonymous = cast.get("p=" + cast.p4.value());
        assertThat(anonymous.status()).isEqualTo(404);
        assertThat(anonymous.flags()).isEqualTo(FrontFlags.notFound());
        assertThat(anonymous.shownPost()).isEmpty();
        assertThat(cast.get("p=" + cast.p4.value(), as(cast.erin)).status()).isEqualTo(404);
        Instant before = Instant.now();
        FrontRequest carol = cast.get("p=" + cast.p4.value(), as(cast.carol));
        assertThat(carol.status()).isEqualTo(200);
        assertThat(carol.flags().preview()).isTrue();
        assertThat(carol.flags().single()).isTrue();
        assertThat(Duration.between(before, carol.shownAt()).abs()).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void aScheduledPostKeepsItsDate() throws Exception {
        FrontRequest bob = cast.get("p=" + cast.p5.value(), as(cast.bob));
        assertThat(bob.status()).isEqualTo(200);
        assertThat(bob.flags().preview()).isTrue();
        assertThat(bob.shownAt()).isEqualTo(Instant.parse("2027-01-01T08:00:00Z"));
        assertThat(cast.get("p=" + cast.p5.value(), as(cast.carol)).status()).isEqualTo(404);
    }

    @Test
    void aPrivatePostNeedsReadPost() throws Exception {
        assertThat(cast.get("p=" + cast.p6.value()).status()).isEqualTo(404);
        assertThat(cast.get("p=" + cast.p6.value(), as(cast.erin)).status()).isEqualTo(404);
        FrontRequest alice = cast.get("p=" + cast.p6.value(), as(cast.alice));
        assertThat(alice.status()).isEqualTo(200);
        assertThat(alice.flags().preview()).isFalse();
        assertThat(alice.shownAt()).isEqualTo(Instant.parse("2026-04-15T08:00:00Z"));
    }

    @Test
    void aDraftPageIsNotFoundForOthers() throws Exception {
        assertThat(cast.get("page_id=" + cast.hidden25.value()).status()).isEqualTo(404);
        FrontRequest alice = cast.get("page_id=" + cast.hidden25.value(), as(cast.alice));
        assertThat(alice.status()).isEqualTo(200);
        assertThat(alice.flags().preview()).isTrue();
        assertThat(alice.flags().page()).isTrue();
    }

    @Test
    void anAttachmentOfADraftFollowsItsParent() throws Exception {
        assertThat(cast.get("attachment_id=" + cast.draftShot32.value()).status())
                .isEqualTo(404);
        FrontRequest carol = cast.get("attachment_id=" + cast.draftShot32.value(), as(cast.carol));
        assertThat(carol.status()).isEqualTo(200);
        assertThat(carol.flags().preview()).isTrue();
        assertThat(carol.flags().attachment()).isTrue();
    }

    @Test
    void editPostIsCheckedOnTheAttachmentItself() throws Exception {
        // front-facts FQ-G10: carol sees her own upload attached to alice's draft, not alice's upload attached to hers
        PostId aliceDraft = TestData.post(
                dataSource, cast.site, "post", "draft", "alice-draft", cast.alice, null, "2026-02-02T08:00:00Z", false);
        PostId aliceShot = TestData.post(
                dataSource,
                cast.site,
                "attachment",
                "inherit",
                "alice-shot",
                cast.alice,
                cast.p4,
                "2026-02-01T08:31:00Z",
                false);
        PostId carolShot = TestData.post(
                dataSource,
                cast.site,
                "attachment",
                "inherit",
                "carol-shot",
                cast.carol,
                aliceDraft,
                "2026-02-02T08:30:00Z",
                false);
        assertThat(cast.get("attachment_id=" + aliceShot.value(), as(cast.carol))
                        .status())
                .isEqualTo(404);
        FrontRequest own = cast.get("attachment_id=" + carolShot.value(), as(cast.carol));
        assertThat(own.status()).isEqualTo(200);
        assertThat(own.flags().preview()).isTrue();
        assertThat(cast.get("attachment_id=" + carolShot.value(), as(cast.erin)).status())
                .isEqualTo(404);
        assertThat(cast.get("attachment_id=" + aliceShot.value(), as(cast.bob)).status())
                .isEqualTo(200);
    }

    @Test
    void anUnattachedAttachmentIsSingle() throws Exception {
        FrontRequest logo = cast.get("attachment_id=" + cast.logo31.value());
        assertThat(logo.status()).isEqualTo(200);
        assertThat(logo.flags().attachment()).isTrue();
        assertThat(logo.flags().single()).isTrue();
        assertThat(logo.flags().page()).isFalse();
        // reached through its path, it is a page at parse time and single after the fetch
        FrontRequest byPath = cast.get("pagename=logo");
        assertThat(byPath.status()).isEqualTo(200);
        assertThat(byPath.flags().attachment()).isTrue();
        assertThat(byPath.flags().single()).isTrue();
        assertThat(byPath.flags().page()).isFalse();
        assertThat(id(byPath)).isEqualTo(cast.logo31.value());
    }

    @Test
    void emptyPagesAndArchives() throws Exception {
        cast.option("posts_per_page", "3");
        assertThat(cast.get("paged=4").status()).isEqualTo(404);
        FrontRequest third = cast.get("paged=3");
        assertThat(third.status()).isEqualTo(200);
        assertThat(ids(third)).containsExactly(cast.p7);
        cast.dropOption("posts_per_page");
        assertThat(cast.get("cat=" + cast.news.value() + "&paged=2").status()).isEqualTo(404);
        assertThat(cast.get("year=2024").status()).isEqualTo(404);
        FrontRequest search = cast.get("s=zzz");
        assertThat(search.status()).isEqualTo(200);
        assertThat(search.posts().posts()).isEmpty();
        assertThat(cast.get("tag=nope").status()).isEqualTo(404);
        // an existing term without posts is a 200
        FrontRequest paint = cast.get("tag=food&cat=" + cast.news.value());
        assertThat(paint.posts().posts()).isEmpty();
        assertThat(paint.status()).isEqualTo(200);
        assertThat(paint.queriedObject()).containsInstanceOf(QueriedObject.Term.class);
    }

    @Test
    void authorArchives() throws Exception {
        FrontRequest erin = cast.get("author=" + cast.erin.value());
        assertThat(erin.status()).isEqualTo(200);
        assertThat(erin.posts().posts()).isEmpty();
        assertThat(erin.queriedObject()).containsInstanceOf(QueriedObject.Author.class);
        assertThat(cast.get("author=999999").status()).isEqualTo(404);
        assertThat(cast.get("author_name=erin-" + cast.site.value()).status()).isEqualTo(200);
        assertThat(cast.get("author_name=nobody").status()).isEqualTo(404);
        FrontRequest carol = cast.get("author_name=carol-" + cast.site.value());
        assertThat(carol.status()).isEqualTo(200);
        assertThat(ids(carol)).containsExactly(cast.p9, cast.p3);
    }

    @Test
    void contentPages() throws Exception {
        assertThat(cast.get("p=" + cast.p10.value() + "&page=4").status()).isEqualTo(404);
        assertThat(cast.get("p=" + cast.p1.value() + "&page=2").status()).isEqualTo(404);
        FrontRequest second = cast.get("p=" + cast.p10.value() + "&page=2");
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.page()).isEqualTo(2);
        // page 0 is no page (handle_404 reads the parser's variable: "0" is empty)
        assertThat(cast.get("p=" + cast.p1.value() + "&page=0").status()).isEqualTo(200);
    }

    @Test
    void aBadPreviewPairIsForbiddenBeforeAnyQuery() throws Exception {
        FrontRequest bad = cast.get(
                "p=" + cast.p9.value() + "&preview=true&preview_id=" + cast.p9.value() + "&preview_nonce=bad",
                as(cast.alice));
        assertThat(bad.status()).isEqualTo(403);
        assertThat(bad.message()).contains("Sorry, you are not allowed to preview drafts.");
        assertThat(bad.query()).isEmpty();
    }

    @Test
    void aValidPairShowsTheLatestAutosave() throws Exception {
        String token = Identities.onSite(cast.site, Identities.user(cast.alice), () -> {
            Arc.container()
                    .instance(Revisions.class)
                    .get()
                    .autosave(
                            cast.p9,
                            AutosaveChanges.builder()
                                    .title("Autosaved recipe")
                                    .content("Autosaved content")
                                    .build());
            return Arc.container().instance(PreviewTokens.class).get().issue(cast.p9, cast.alice);
        });
        FrontRequest preview = cast.get(
                "p=" + cast.p9.value() + "&preview=true&preview_id=" + cast.p9.value() + "&preview_nonce=" + token,
                as(cast.alice));
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.previewArmed()).isTrue();
        assertThat(preview.flags().preview()).isTrue();
        PostSnapshot shown = preview.shownPost().orElseThrow();
        assertThat(shown.title()).isEqualTo("Autosaved recipe");
        assertThat(shown.content()).isEqualTo("Autosaved content");
        // the stored post is not changed, and nobody else gets the overlay
        FrontRequest plain = cast.get("p=" + cast.p9.value(), as(cast.alice));
        assertThat(plain.shownPost().orElseThrow().title()).isEqualTo("Secret recipe");
    }

    /** {@code user}'s preview token of {@code post}, issued as that user. */
    private String token(PostId post, UserId user) throws Exception {
        return Identities.onSite(
                cast.site,
                Identities.user(user),
                () -> Arc.container().instance(PreviewTokens.class).get().issue(post, user));
    }

    /** An autosave of {@code post} by {@code user}. */
    private void autosave(PostId post, UserId user, String title, String content) throws Exception {
        Identities.onSite(cast.site, Identities.user(user), () -> {
            Arc.container()
                    .instance(Revisions.class)
                    .get()
                    .autosave(
                            post,
                            AutosaveChanges.builder()
                                    .title(title)
                                    .content(content)
                                    .build());
            return null;
        });
    }

    private static String pair(PostId shown, PostId previewId, String token) {
        return "p=" + shown.value() + "&preview=true&preview_id=" + previewId.value() + "&preview_nonce=" + token;
    }

    @Test
    void aPairOfAnotherUserOrPostIsForbidden() throws Exception {
        // carol's valid token of her post, used by alice
        String carols = token(cast.p3, cast.carol);
        FrontRequest borrowed = cast.get(pair(cast.p3, cast.p3, carols), as(cast.alice));
        assertThat(borrowed.status()).isEqualTo(403);
        assertThat(borrowed.message()).contains(FrontRequest.CANNOT_PREVIEW);
        assertThat(borrowed.query()).isEmpty();
        assertThat(cast.get(pair(cast.p3, cast.p3, carols), as(cast.carol)).status())
                .isEqualTo(200);
        // alice's token of one post with the id of another
        String alices = token(cast.p9, cast.alice);
        FrontRequest otherPost = cast.get(pair(cast.p8, cast.p8, alices), as(cast.alice));
        assertThat(otherPost.status()).isEqualTo(403);
        assertThat(otherPost.query()).isEmpty();
    }

    @Test
    void aValidPairWithoutEditPostOnTheShownPostShowsNoAutosave() throws Exception {
        autosave(cast.p8, cast.alice, "Autosaved car", "Autosaved car content");
        // carol's pair is valid for her own post, but she may not edit bob's
        FrontRequest carol = cast.get(pair(cast.p8, cast.p3, token(cast.p3, cast.carol)), as(cast.carol));
        assertThat(carol.status()).isEqualTo(200);
        assertThat(carol.previewArmed()).isTrue();
        assertThat(carol.flags().preview()).isTrue();
        assertThat(carol.shownPost().orElseThrow().title()).isEqualTo("Car painted red");
        // alice may edit it: the autosave is shown
        FrontRequest alice = cast.get(pair(cast.p8, cast.p8, token(cast.p8, cast.alice)), as(cast.alice));
        assertThat(alice.shownPost().orElseThrow().title()).isEqualTo("Autosaved car");
    }

    @Test
    void theContentPageCountReadsTheAutosave() throws Exception {
        autosave(cast.p1, cast.alice, "Hello again", "One<!--nextpage-->Two");
        // handle_404 counts the markers of the shown post, which an armed preview has overlaid
        String token = token(cast.p1, cast.alice);
        FrontRequest second = cast.get(pair(cast.p1, cast.p1, token) + "&page=2", as(cast.alice));
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.shownPost().orElseThrow().content()).isEqualTo("One<!--nextpage-->Two");
        assertThat(cast.get(pair(cast.p1, cast.p1, token) + "&page=3", as(cast.alice))
                        .status())
                .isEqualTo(404);
        assertThat(cast.get("p=" + cast.p1.value() + "&page=2", as(cast.alice)).status())
                .isEqualTo(404);
    }

    @Test
    void thePreviewedImageAndFormat() throws Exception {
        Jdbc.update(
                dataSource,
                "update qw_post set meta = " + TestDb.json() + " where id = ?",
                "{\"_thumbnail_id\":[5]}",
                cast.p2.value());
        // post_format is also the format taxonomy's query variable: the post must have the format to be found
        TestData.assign(
                dataSource,
                cast.site,
                cast.p2,
                TestData.term(dataSource, cast.site, "post_format", "Gallery", "post-format-gallery", null));
        String token = token(cast.p2, cast.alice);
        String base = pair(cast.p2, cast.p2, token);
        // main-query.out "T8-R preview filters": an empty _thumbnail_id (0 or nothing) leaves the image, a value of 0
        // or less after (int) removes it, a positive one replaces it
        assertThat(thumbnail(cast.get(base + "&_thumbnail_id=0", as(cast.alice))))
                .contains(5L);
        assertThat(thumbnail(cast.get(base + "&_thumbnail_id=", as(cast.alice))))
                .contains(5L);
        assertThat(thumbnail(cast.get(base + "&_thumbnail_id=-1", as(cast.alice))))
                .isEmpty();
        assertThat(thumbnail(cast.get(base + "&_thumbnail_id=x", as(cast.alice))))
                .isEmpty();
        assertThat(thumbnail(cast.get(base + "&_thumbnail_id=7", as(cast.alice))))
                .contains(7L);
        // the format's key is sanitized (sanitize_key) before the lookup
        assertThat(format(cast.get(base + "&post_format=GALLERY", as(cast.alice))))
                .contains("gallery");
        assertThat(format(cast.get(base + "&post_format=gallery", as(cast.alice))))
                .contains("gallery");
    }

    private static java.util.Optional<Long> thumbnail(FrontRequest request) {
        List<JsonNode> values = request.shownPost().orElseThrow().meta().get("_thumbnail_id");
        return values == null || values.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of(values.get(0).asLong());
    }

    private static java.util.Optional<String> format(FrontRequest request) {
        List<JsonNode> values = request.shownPost().orElseThrow().meta().get("post_format");
        return values == null || values.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of(values.get(0).asText());
    }

    @Test
    void unitsWithoutTheUnitsAboveFindPosts() throws Exception {
        // main-query.out FQ13: WordPress compares MONTH(post_date) = 5, DAYOFMONTH(post_date) = 20, HOUR(post_date) = 8
        FrontRequest may = cast.get("monthnum=5");
        assertThat(may.status()).isEqualTo(200);
        assertThat(may.flags().month()).isTrue();
        assertThat(ids(may)).containsExactly(cast.p8);
        assertThat(ids(cast.get("day=20"))).containsExactly(cast.p8);
        assertThat(ids(cast.get("year=2026&day=20"))).containsExactly(cast.p8);
        assertThat(ids(cast.get("year=2025&day=20"))).isEmpty();
        FrontRequest eight = cast.get("hour=8");
        assertThat(eight.status()).isEqualTo(200);
        assertThat(ids(eight)).containsExactly(cast.p10, cast.p9, cast.p8, cast.p3, cast.p2, cast.p1, cast.p7);
        assertThat(ids(cast.get("hour=8&minute=0&second=0"))).hasSize(7);
        assertThat(ids(cast.get("monthnum=1&hour=8"))).containsExactly(cast.p1);
        // a date archive that finds nothing is a 404, as in WordPress
        assertThat(cast.get("day=31").status()).isEqualTo(404);
        assertThat(cast.get("hour=9").status()).isEqualTo(404);
    }

    @Test
    void anUnregisteredPostTypeIsDroppedByTheParser() throws Exception {
        // WordPress drops post_type=any from the URL (front-facts FQ-G15): the home list of posts, sticky first
        FrontRequest any = cast.get("post_type=any");
        assertThat(any.status()).isEqualTo(200);
        assertThat(ids(any)).containsExactly(cast.p7, cast.p10, cast.p9, cast.p8, cast.p3, cast.p2, cast.p1);
    }

    @Test
    void errorsAndReservedVariables() throws Exception {
        FrontRequest forbidden = cast.get("error=403");
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.query()).isEmpty();
        assertThat(cast.get("error=404").status()).isEqualTo(404);
        // front-facts FR16-error: WordPress sends the error's status after resolving the page as usual
        FrontRequest gone = cast.get("error=410");
        assertThat(gone.status()).isEqualTo(410);
        assertThat(gone.flags().home()).isTrue();
        assertThat(ids(gone)).hasSize(7);
        FrontRequest goneNotFound = cast.get("p=-1&error=410");
        assertThat(goneNotFound.status()).isEqualTo(410);
        assertThat(goneNotFound.flags()).isEqualTo(FrontFlags.notFound());
        // a status WordPress has no description for is not sent
        assertThat(cast.get("error=499").status()).isEqualTo(200);
        assertThat(cast.get("error=500").status()).isEqualTo(500);
        assertThat(cast.get("error=404").flags()).isEqualTo(FrontFlags.notFound());
        FrontRequest feed = cast.get("feed=rss2");
        assertThat(feed.status()).isEqualTo(404);
        assertThat(feed.flags().feed()).isTrue();
        assertThat(feed.query()).isEmpty();
        for (String reserved : new String[] {"embed=1", "tb=1", "robots=1", "favicon=1", "sitemap=index", "cpage=2"}) {
            assertThat(cast.get(reserved).status()).as(reserved).isEqualTo(404);
        }
    }

    @Test
    void aNotFoundFromTheQueryParserIsSentWithStatus200() throws Exception {
        // front-facts FQ-G2 and FQ-G3: WordPress answers 200 with the 404 template
        FrontRequest negative = cast.get("p=-1");
        assertThat(negative.status()).isEqualTo(200);
        assertThat(negative.flags()).isEqualTo(FrontFlags.notFound());
        assertThat(negative.query()).isEmpty();
        FrontRequest month = cast.get("monthnum=13");
        assertThat(month.status()).isEqualTo(200);
        assertThat(month.flags().is404()).isTrue();
    }

    @Test
    void theStaticFrontPage() throws Exception {
        cast.option("show_on_front", "\"page\"");
        cast.option("page_on_front", Long.toString(cast.home22.value()));
        cast.option("page_for_posts", Long.toString(cast.blog21.value()));
        FrontRequest front = cast.get("");
        assertThat(front.status()).isEqualTo(200);
        assertThat(id(front)).isEqualTo(cast.home22.value());
        assertThat(frontPage(front)).isTrue();
        // front-facts FQ-G6: paged moves into page, and handle_404 reads the parser's empty page: 200
        FrontRequest second = cast.get("paged=2");
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.page()).isEqualTo(2);
        assertThat(id(second)).isEqualTo(cast.home22.value());
        // the posts page by id: the queried object is the page, the list is the home list
        FrontRequest blog = cast.get("page_id=" + cast.blog21.value());
        assertThat(blog.status()).isEqualTo(200);
        assertThat(blog.flags().postsPage()).isTrue();
        assertThat(blog.queriedObject()).containsInstanceOf(QueriedObject.PostsPage.class);
        assertThat(ids(blog).get(0)).isEqualTo(cast.p7);
        assertThat(cast.get("pagename=blog&paged=2").status()).isEqualTo(404);
        assertThat(cast.get("pagename=blog&page=2").status()).isEqualTo(404);
    }

    /** Whether the request is for the front page, asked on the site. */
    private static boolean frontPage(FrontRequest request) throws Exception {
        return Identities.onSite(
                cast.site,
                Identities.anonymous(),
                () -> request.flags()
                        .isFrontPage(
                                Arc.container()
                                        .instance(SiteContext.class)
                                        .get()
                                        .options(),
                                request.queriedObject()));
    }
}
