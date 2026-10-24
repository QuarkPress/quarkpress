package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostMeta;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostType;
import org.quarkpress.posts.Posts;
import org.quarkpress.revisions.AutosaveChanges;
import org.quarkpress.revisions.AutosaveResult;
import org.quarkpress.revisions.RevisionException;
import org.quarkpress.revisions.RevisionQuery;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.revisions.Revisions;

/** Autosaves follow WordPress's REST autosaves endpoint. */
class AutosaveDbTest {

    /** Revisioned but not autosaved (no editor). */
    @PostType(
            key = "sheet",
            supports = {"title", "revisions"})
    static class Sheet {}

    /** Autosaved but not revisioned. */
    @PostType(
            key = "card",
            supports = {"title", "editor"})
    static class Card {}

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(AutosaveDbTest.class, Sheet.class, Card.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    PostMeta meta;

    @Inject
    Revisions revisions;

    @Inject
    TestClock clock;

    private SiteId site;
    private UserId author;
    private UserId editor;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
        author = TestData.profile(dataSource, "author");
        TestData.member(dataSource, TestData.MAIN, author, "[\"author\"]");
        editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
    }

    private PostSnapshot create(UserId user, PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(user), () -> posts.create(changes.build()));
    }

    private AutosaveResult autosave(UserId user, PostId post, AutosaveChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(user), () -> revisions.autosave(post, changes.build()));
    }

    private long count(PostId post) {
        return Jdbc.count(dataSource, "select count(*) from qp_revision where post_id = ?", post.value());
    }

    private String post(PostId post, String column) {
        return Jdbc.strings(dataSource, "select " + column + " from qp_post where id = ?", post.value())
                .get(0);
    }

    @Test
    void anAuthorsOwnDraftIsUpdatedWithoutARevisionAndItsMetaIgnored() throws Exception {
        PostSnapshot draft =
                create(author, PostChanges.builder().title("Draft").content("x").status("draft"));
        Identities.onSite(
                site, Identities.user(author), () -> meta.update(draft.id(), "footnotes", TextNode.valueOf("[1]")));
        clock.set(Instant.parse("2026-01-15T12:05:00Z"));
        AutosaveResult result = autosave(
                author, draft.id(), AutosaveChanges.builder().content("xy").meta("footnotes", TextNode.valueOf("[2]")));
        assertThat(result).isInstanceOfSatisfying(AutosaveResult.Draft.class, updated -> {
            assertThat(updated.post().content()).isEqualTo("xy");
            assertThat(updated.post().title()).isEqualTo("Draft");
            assertThat(updated.post().modifiedAt()).isEqualTo(Instant.parse("2026-01-15T12:05:00Z"));
            assertThat(updated.post().meta("footnotes")).containsExactly(TextNode.valueOf("[1]"));
        });
        assertThat(count(draft.id())).isZero();
        PostSnapshot auto = create(author, PostChanges.builder().title("Auto").status("auto-draft"));
        assertThat(autosave(author, auto.id(), AutosaveChanges.builder().title("Auto 2")))
                .isInstanceOf(AutosaveResult.Draft.class);
        assertThat(post(auto.id(), "title")).isEqualTo("Auto 2");
        assertThat(post(auto.id(), "status")).isEqualTo("auto-draft");
    }

    @Test
    void pendingPostsOthersDraftsAndPublishedPostsGetAnAutosaveRow() throws Exception {
        PostSnapshot pending =
                create(author, PostChanges.builder().title("P").content("c").status("pending"));
        AutosaveResult own =
                autosave(author, pending.id(), AutosaveChanges.builder().content("c2"));
        assertThat(own).isInstanceOfSatisfying(AutosaveResult.Stored.class, stored -> {
            assertThat(stored.written()).isTrue();
            assertThat(stored.autosave().post()).isEqualTo(pending.id());
            assertThat(stored.autosave().author()).contains(author);
            assertThat(stored.autosave().autosave()).isTrue();
            assertThat(stored.autosave().content()).isEqualTo("c2");
            assertThat(stored.autosave().createdAt()).isEqualTo(Instant.parse("2026-01-15T12:00:00Z"));
        });
        assertThat(post(pending.id(), "content")).isEqualTo("c");
        PostSnapshot draft =
                create(author, PostChanges.builder().title("D").content("d").status("draft"));
        assertThat(autosave(editor, draft.id(), AutosaveChanges.builder().content("d2")))
                .isInstanceOfSatisfying(
                        AutosaveResult.Stored.class,
                        stored -> assertThat(stored.autosave().author()).contains(editor));
        assertThat(post(draft.id(), "content")).isEqualTo("d");
        assertThat(count(draft.id())).isEqualTo(1);
    }

    @Test
    void aFirstAutosaveIsStoredWithoutComparisonAndAnIdenticalOneIsReturnedUntouched() throws Exception {
        PostSnapshot published = create(
                editor,
                PostChanges.builder().author(author).title("T").content("Body").status("publish"));
        AutosaveResult first =
                autosave(author, published.id(), AutosaveChanges.builder().content("Body"));
        RevisionSnapshot stored = ((AutosaveResult.Stored) first).autosave();
        assertThat(((AutosaveResult.Stored) first).written()).isTrue();
        assertThat(stored.content()).isEqualTo("Body");
        assertThat(stored.title()).isEmpty();
        assertThat(stored.excerpt()).isEmpty();
        clock.set(Instant.parse("2026-01-15T12:09:00Z"));
        AutosaveResult again =
                autosave(author, published.id(), AutosaveChanges.builder().content("Body"));
        assertThat(again).isInstanceOfSatisfying(AutosaveResult.Stored.class, same -> {
            assertThat(same.written()).isFalse();
            assertThat(same.autosave()).isEqualTo(stored);
        });
        AutosaveResult changed =
                autosave(author, published.id(), AutosaveChanges.builder().content("Body 2"));
        assertThat(changed).isInstanceOfSatisfying(AutosaveResult.Stored.class, updated -> {
            assertThat(updated.written()).isTrue();
            assertThat(updated.autosave().id()).isEqualTo(stored.id());
            assertThat(updated.autosave().content()).isEqualTo("Body 2");
            assertThat(updated.autosave().createdAt()).isEqualTo(Instant.parse("2026-01-15T12:09:00Z"));
        });
        // back to the post's text: different from the autosave but not from the post, so nothing is written
        clock.set(Instant.parse("2026-01-15T12:10:00Z"));
        AutosaveResult back =
                autosave(author, published.id(), AutosaveChanges.builder().content("Body"));
        assertThat(((AutosaveResult.Stored) back).written()).isFalse();
        assertThat(((AutosaveResult.Stored) back).autosave().content()).isEqualTo("Body 2");
        assertThat(count(published.id())).isEqualTo(1);
        // a title alone: compared on the title, the stored content stays
        AutosaveResult title =
                autosave(author, published.id(), AutosaveChanges.builder().title("T2"));
        assertThat(((AutosaveResult.Stored) title).autosave().title()).isEqualTo("T2");
        assertThat(((AutosaveResult.Stored) title).autosave().content()).isEqualTo("Body 2");
        assertThat(count(published.id())).isEqualTo(1);
    }

    @Test
    void revisionedMetaIsCopiedFromThePostThenReplacedByWhatIsGiven() throws Exception {
        PostSnapshot published = create(
                editor,
                PostChanges.builder().author(author).title("T").content("Body").status("publish"));
        Identities.onSite(
                site, Identities.user(editor), () -> meta.update(published.id(), "footnotes", TextNode.valueOf("[1]")));
        RevisionSnapshot copy = ((AutosaveResult.Stored) autosave(
                        author, published.id(), AutosaveChanges.builder().content("Body")))
                .autosave();
        assertThat(copy.meta("footnotes")).containsExactly(TextNode.valueOf("[1]"));
        // the same text with the post's own meta: not different
        assertThat(((AutosaveResult.Stored) autosave(
                                author,
                                published.id(),
                                AutosaveChanges.builder().content("Body").meta("footnotes", TextNode.valueOf("[1]"))))
                        .written())
                .isFalse();
        RevisionSnapshot replaced = ((AutosaveResult.Stored) autosave(
                        author,
                        published.id(),
                        AutosaveChanges.builder().content("Body").meta("footnotes", TextNode.valueOf("[2]"))))
                .autosave();
        assertThat(replaced.meta("footnotes")).containsExactly(TextNode.valueOf("[2]"));
        assertThat(Identities.onSite(site, Identities.user(editor), () -> meta.get(published.id(), "footnotes")))
                .containsExactly(TextNode.valueOf("[1]"));
        assertThatThrownBy(() -> autosave(
                        author,
                        published.id(),
                        AutosaveChanges.builder().content("x").meta("color", TextNode.valueOf("red"))))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(PostException.class)
                .hasMessageContaining("color");
    }

    @Test
    void autosavesNeedNeitherTheLimitNorTheFeatureButTheTypeMustBeAutosaved() throws Exception {
        PostSnapshot card = create(
                editor,
                PostChanges.builder().author(author).type("card").title("C").status("publish"));
        assertThat(autosave(author, card.id(), AutosaveChanges.builder().title("C2")))
                .isInstanceOf(AutosaveResult.Stored.class);
        TestData.featureOff(dataSource, site, "revisions");
        PostSnapshot post =
                create(editor, PostChanges.builder().author(author).title("P").status("publish"));
        assertThat(autosave(author, post.id(), AutosaveChanges.builder().title("P2")))
                .isInstanceOf(AutosaveResult.Stored.class);
        assertThat(Identities.onSite(site, Identities.user(editor), () -> revisions.autosavesOf(post.id())))
                .hasSize(1);
        assertThat(Identities.onSite(
                        site, Identities.user(editor), () -> revisions.ofPost(post.id(), RevisionQuery.all())))
                .isEmpty();
        PostSnapshot sheet = create(
                editor,
                PostChanges.builder().author(author).type("sheet").title("S").status("publish"));
        assertThatThrownBy(() ->
                        autosave(author, sheet.id(), AutosaveChanges.builder().title("S2")))
                .isInstanceOfSatisfying(
                        RevisionException.class,
                        refused -> assertThat(refused.code()).isEqualTo("autosave_not_supported"));
        assertThatThrownBy(() -> autosave(
                        author,
                        PostId.of(999_999_999L),
                        AutosaveChanges.builder().title("x")))
                .isInstanceOfSatisfying(
                        PostException.class,
                        refused -> assertThat(refused.code()).isEqualTo("invalid_post"));
    }

    @Test
    void theSystemsAutosaveBelongsToNobodyAndTextIsFilteredForTheCaller() throws Exception {
        PostSnapshot published = create(
                editor,
                PostChanges.builder().author(author).title("T").content("Body").status("publish"));
        RevisionSnapshot system = ((AutosaveResult.Stored) Identities.onSite(
                        site,
                        Identities.system(),
                        () -> revisions.autosave(
                                published.id(),
                                AutosaveChanges.builder().content("sys").build())))
                .autosave();
        assertThat(system.author()).isEmpty();
        RevisionSnapshot again = ((AutosaveResult.Stored) Identities.onSite(
                        site,
                        Identities.system(),
                        () -> revisions.autosave(
                                published.id(),
                                AutosaveChanges.builder().content("sys 2").build())))
                .autosave();
        assertThat(again.id()).isEqualTo(system.id());
        RevisionSnapshot theirs = ((AutosaveResult.Stored) autosave(
                        author, published.id(), AutosaveChanges.builder().content("<script>x()</script>mine")))
                .autosave();
        assertThat(theirs.content()).isEqualTo("x()mine");
        // the update path filters as well, and keeps the row
        RevisionSnapshot refiltered = ((AutosaveResult.Stored) autosave(
                        author,
                        published.id(),
                        AutosaveChanges.builder()
                                .content("<script>y()</script>again")
                                .title("<b>t</b><script>z()</script>")))
                .autosave();
        assertThat(refiltered.id()).isEqualTo(theirs.id());
        assertThat(refiltered.content()).isEqualTo("y()again");
        assertThat(refiltered.title()).doesNotContain("<script").contains("z()");
        assertThat(theirs.id()).isNotEqualTo(system.id());
        assertThat(Identities.onSite(
                                site,
                                Identities.user(editor),
                                () -> revisions.autosaveOf(published.id(), Optional.empty()))
                        .map(RevisionSnapshot::id))
                .contains(theirs.id());
    }

    @Test
    void aRevisionedKeyThePostDoesNotStoreAlwaysCountsAsDifferent() throws Exception {
        PostSnapshot published = create(
                editor,
                PostChanges.builder().author(author).title("T").content("Body").status("publish"));
        autosave(author, published.id(), AutosaveChanges.builder().content("Body"));
        clock.set(Instant.parse("2026-01-15T12:01:00Z"));
        AutosaveResult same = autosave(
                author,
                published.id(),
                AutosaveChanges.builder().content("Body").meta("footnotes", TextNode.valueOf("")));
        assertThat(same).isInstanceOfSatisfying(AutosaveResult.Stored.class, stored -> {
            assertThat(stored.written()).isTrue();
            assertThat(stored.autosave().meta("footnotes")).containsExactly(TextNode.valueOf(""));
            assertThat(stored.autosave().createdAt()).isEqualTo(Instant.parse("2026-01-15T12:01:00Z"));
        });
    }

    @Test
    void severalAuthorlessAutosavesLeaveTheSystemTheNewestToUpdateAndAUsersOwnIsSeparate() throws Exception {
        PostSnapshot published = create(
                editor,
                PostChanges.builder().author(author).title("T").content("Body").status("publish"));
        TestData.revision(dataSource, site, published.id(), null, "", "old", "", "{}", true, "2026-01-15T10:00:00Z");
        TestData.revision(dataSource, site, published.id(), null, "", "new", "", "{}", true, "2026-01-15T11:00:00Z");
        RevisionSnapshot updated = ((AutosaveResult.Stored) Identities.onSite(
                        site,
                        Identities.system(),
                        () -> revisions.autosave(
                                published.id(),
                                AutosaveChanges.builder().content("sys").build())))
                .autosave();
        assertThat(Jdbc.strings(
                        dataSource,
                        "select content from qp_revision where id = ?",
                        updated.id().value()))
                .containsExactly("sys");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select content from qp_revision where post_id = ? order by id",
                        published.id().value()))
                .containsExactly("old", "sys");
        // a user has an autosave of their own besides the authorless ones, owned in the unique column
        RevisionSnapshot taken = ((AutosaveResult.Stored) autosave(
                        author, published.id(), AutosaveChanges.builder().content("mine")))
                .autosave();
        assertThat(taken.author()).contains(author);
        assertThat(taken.id()).isNotEqualTo(updated.id());
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_revision where post_id = ? and autosave_owner = ?",
                        published.id().value(),
                        author.value()))
                .isEqualTo(1);
    }

    @Test
    void aFirstAutosaveWithMetaGivenHoldsTheGivenValueNotThePostsCopy() throws Exception {
        PostSnapshot published = create(
                editor,
                PostChanges.builder().author(author).title("T").content("Body").status("publish"));
        Identities.onSite(
                site, Identities.user(editor), () -> meta.update(published.id(), "footnotes", TextNode.valueOf("[1]")));
        RevisionSnapshot first = ((AutosaveResult.Stored) autosave(
                        author,
                        published.id(),
                        AutosaveChanges.builder().content("Body").meta("footnotes", TextNode.valueOf("[2]"))))
                .autosave();
        assertThat(first.meta("footnotes")).containsExactly(TextNode.valueOf("[2]"));
    }
}
