package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostException;
import org.quarkpress.revisions.RevisionCount;
import org.quarkpress.revisions.RevisionException;
import org.quarkpress.revisions.RevisionQuery;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.revisions.Revisions;

class ReadsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(ReadsDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Revisions revisions;

    private SiteId site;
    private UserId editor;
    private UserId other;
    private PostId post;
    private RevisionId r8;
    private RevisionId r9;
    private RevisionId a10;
    private RevisionId a11;
    private RevisionId a12;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
        editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        other = TestData.profile(dataSource, "other");
        TestData.member(dataSource, TestData.MAIN, other, "[\"editor\"]");
        post = TestData.postBy(dataSource, site, editor, "post", "publish", "P");
        // two regular revisions in the same second, then three autosaves: other's, the editor's, and nobody's
        r8 = TestData.revision(dataSource, site, post, editor, "r8", "", "", "{}", false, "2026-01-15T12:00:00Z");
        r9 = TestData.revision(dataSource, site, post, editor, "r9", "", "", "{}", false, "2026-01-15T12:00:00Z");
        a10 = TestData.revision(dataSource, site, post, other, "a10", "", "", "{}", true, "2026-01-15T12:05:00Z");
        a11 = TestData.revision(dataSource, site, post, editor, "a11", "", "", "{}", true, "2026-01-15T12:05:00Z");
        UserId gone = TestData.profile(dataSource, "gone");
        a12 = TestData.revision(dataSource, site, post, gone, "a12", "", "", "{}", true, "2026-01-15T12:03:00Z");
        // its author is deleted afterwards: the revision stays, without author
        Jdbc.update(dataSource, "delete from qp_user_profile where id = ?", gone.value());
    }

    private <T> T asEditor(Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.user(editor), work);
    }

    private static List<RevisionId> ids(List<RevisionSnapshot> found) {
        return found.stream().map(RevisionSnapshot::id).toList();
    }

    @Test
    void revisionsComeNewestFirstThenByHighestIdAndFollowTheQuery() throws Exception {
        assertThat(ids(asEditor(() -> revisions.ofPost(post, RevisionQuery.all()))))
                .containsExactly(a11, a10, a12, r9, r8);
        assertThat(ids(asEditor(() -> revisions.ofPost(
                        post, RevisionQuery.builder().ascending(true).build()))))
                .containsExactly(r8, r9, a12, a10, a11);
        assertThat(ids(asEditor(() ->
                        revisions.ofPost(post, RevisionQuery.builder().limit(2).build()))))
                .containsExactly(a11, a10);
        assertThat(ids(asEditor(() -> revisions.ofPost(
                        post, RevisionQuery.builder().limit(2).offset(1).build()))))
                .containsExactly(a10, a12);
        assertThat(ids(asEditor(() -> revisions.ofPost(
                        post, RevisionQuery.builder().author(other).build()))))
                .containsExactly(a10);
        assertThat(ids(asEditor(() -> revisions.ofPost(
                        post,
                        RevisionQuery.builder()
                                .autosaves(RevisionQuery.Autosaves.ONLY)
                                .build()))))
                .containsExactly(a11, a10, a12);
        assertThat(ids(asEditor(() -> revisions.ofPost(
                        post,
                        RevisionQuery.builder()
                                .autosaves(RevisionQuery.Autosaves.EXCLUDE)
                                .build()))))
                .containsExactly(r9, r8);
        assertThatThrownBy(() -> RevisionQuery.builder().limit(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be at least 1");
        assertThatThrownBy(() -> RevisionQuery.builder().offset(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("offset must not be negative");
        assertThatThrownBy(() -> new RevisionQuery(
                        false,
                        OptionalInt.of(0),
                        OptionalInt.empty(),
                        Optional.empty(),
                        RevisionQuery.Autosaves.INCLUDE,
                        true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be at least 1");
        assertThatThrownBy(() -> new RevisionQuery(
                        false,
                        OptionalInt.empty(),
                        OptionalInt.of(-1),
                        Optional.empty(),
                        RevisionQuery.Autosaves.INCLUDE,
                        true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("offset must not be negative");
    }

    @Test
    void aRevisionWithoutAuthorIsListedAndFound() throws Exception {
        RevisionSnapshot nobody = asEditor(() -> revisions.find(a12)).orElseThrow();
        assertThat(nobody.author()).isEmpty();
        assertThat(nobody.title()).isEqualTo("a12");
        assertThat(nobody.autosave()).isTrue();
        assertThat(ids(asEditor(() -> revisions.ofPost(post, RevisionQuery.all()))))
                .contains(a12);
    }

    @Test
    void aDisabledPostListsNothingButSingleReadsCountsAndAutosavesBehaveAsWordPress() throws Exception {
        assertThat(asEditor(() -> revisions.latestAndCount(post))).isEqualTo(new RevisionCount(Optional.of(a11), 5));
        TestData.featureOff(dataSource, site, "revisions");
        assertThat(asEditor(() -> revisions.ofPost(post, RevisionQuery.all()))).isEmpty();
        assertThat(ids(asEditor(() -> revisions.ofPost(
                        post, RevisionQuery.builder().checkEnabled(false).build()))))
                .containsExactly(a11, a10, a12, r9, r8);
        assertThat(asEditor(() -> revisions.find(r8)).map(RevisionSnapshot::title))
                .contains("r8");
        assertThat(ids(asEditor(() -> revisions.autosavesOf(post)))).containsExactly(a11, a10, a12);
        assertThatThrownBy(() -> asEditor(() -> revisions.latestAndCount(post)))
                .isInstanceOfSatisfying(
                        RevisionException.class,
                        refused -> assertThat(refused.code()).isEqualTo("revisions_not_enabled"))
                .hasMessage("Revisions not enabled.");
    }

    @Test
    void theLatestAutosaveOfAnyUserOrOfOneUser() throws Exception {
        assertThat(asEditor(() -> revisions.autosaveOf(post, Optional.empty())).map(RevisionSnapshot::id))
                .contains(a11);
        assertThat(asEditor(() -> revisions.autosaveOf(post, Optional.of(other)))
                        .map(RevisionSnapshot::id))
                .contains(a10);
        UserId third = TestData.profile(dataSource, "third");
        assertThat(asEditor(() -> revisions.autosaveOf(post, Optional.of(third))))
                .isEmpty();
        PostId bare = TestData.postBy(dataSource, site, editor, "post", "publish", "Bare");
        assertThat(asEditor(() -> revisions.autosaveOf(bare, Optional.empty()))).isEmpty();
        assertThat(asEditor(() -> revisions.autosavesOf(bare))).isEmpty();
        assertThat(asEditor(() -> revisions.latestAndCount(bare))).isEqualTo(new RevisionCount(Optional.empty(), 0));
        // a stale autosave (older than the post's modification) is still the answer
        Jdbc.update(
                dataSource,
                "update qp_post set modified_at = " + TestDb.instant() + " where id = ?",
                TestDb.instantText("2026-01-15T13:00:00Z"),
                post.value());
        assertThat(asEditor(() -> revisions.autosaveOf(post, Optional.empty())).map(RevisionSnapshot::id))
                .contains(a11);
    }

    @Test
    void anotherSitesRevisionsAreInvisibleAndAMissingPostIsRefused() throws Exception {
        SiteId elsewhere = TestData.site(dataSource);
        assertThat(Identities.onSite(elsewhere, Identities.user(editor), () -> revisions.find(r8)))
                .isEmpty();
        assertThatThrownBy(() -> Identities.onSite(
                        elsewhere, Identities.user(editor), () -> revisions.ofPost(post, RevisionQuery.all())))
                .isInstanceOfSatisfying(
                        PostException.class,
                        refused -> assertThat(refused.code()).isEqualTo("invalid_post"));
        assertThatThrownBy(() -> asEditor(() -> revisions.latestAndCount(PostId.of(999_999_999L))))
                .isInstanceOfSatisfying(
                        PostException.class,
                        refused -> assertThat(refused.code()).isEqualTo("invalid_post"));
        assertThatThrownBy(() -> asEditor(() -> revisions.autosaveOf(PostId.of(999_999_999L), Optional.empty())))
                .isInstanceOfSatisfying(
                        PostException.class,
                        refused -> assertThat(refused.code()).isEqualTo("invalid_post"));
        assertThat(asEditor(() -> revisions.find(RevisionId.of(999_999_999L)))).isEmpty();
    }
}
