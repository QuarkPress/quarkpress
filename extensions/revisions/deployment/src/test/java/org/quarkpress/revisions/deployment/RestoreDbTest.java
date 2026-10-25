package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostMeta;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.revisions.RevisionException;
import org.quarkpress.revisions.RevisionField;
import org.quarkpress.revisions.RevisionQuery;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.revisions.Revisions;

/** Restoring is an ordinary update of the post with the revision's fields, then the revision's meta. */
class RestoreDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(RestoreDbTest.class);

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

    @Inject
    TransactionManager transactions;

    private SiteId site;
    private UserId editor;
    private UserId admin;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
        editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        admin = TestData.profile(dataSource, "admin");
        TestData.member(dataSource, TestData.MAIN, admin, "[\"administrator\"]");
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
    }

    private PostSnapshot create(UserId user, PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(user), () -> posts.create(changes.build()));
    }

    private PostSnapshot update(UserId user, PostId post, PostChanges.Builder changes) throws Exception {
        clock.set(clock.now().plusSeconds(60));
        return Identities.onSite(site, Identities.user(user), () -> posts.update(post, changes.build()));
    }

    /** The post's revisions oldest first as {@code title|content}. */
    private List<String> rows(PostId post) {
        return Jdbc.strings(
                dataSource,
                "select concat(title, '|', content) from qp_revision where post_id = ?" + " order by created_at, id",
                post.value());
    }

    private RevisionSnapshot revisionTitled(PostId post, String title) throws Exception {
        return Identities.onSite(site, Identities.user(editor), () -> revisions.ofPost(post, RevisionQuery.all()))
                .stream()
                .filter(revision -> revision.title().equals(title))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void anOlderRevisionComesBackAsANewSaveAndTheNewestRestoresWithoutARevision() throws Exception {
        PostSnapshot post =
                create(editor, PostChanges.builder().title("v1").content("one").status("publish"));
        update(editor, post.id(), PostChanges.builder().title("v2").content("two"));
        update(editor, post.id(), PostChanges.builder().title("v3").content("three"));
        assertThat(rows(post.id())).containsExactly("v2|two", "v3|three");
        RevisionSnapshot v2 = revisionTitled(post.id(), "v2");
        clock.set(Instant.parse("2026-01-15T13:00:00Z"));
        PostSnapshot restored =
                Identities.onSite(site, Identities.user(admin), () -> revisions.restore(v2.id(), Set.of()));
        assertThat(restored.id()).isEqualTo(post.id());
        assertThat(restored.title()).isEqualTo("v2");
        assertThat(restored.content()).isEqualTo("two");
        assertThat(restored.status()).isEqualTo("publish");
        assertThat(restored.slug()).isEqualTo(post.slug());
        assertThat(restored.author()).contains(editor);
        assertThat(restored.publishedAt()).isEqualTo(post.publishedAt());
        assertThat(restored.modifiedAt()).isEqualTo(Instant.parse("2026-01-15T13:00:00Z"));
        assertThat(rows(post.id())).containsExactly("v2|two", "v3|three", "v2|two");
        assertThat(Jdbc.strings(
                                dataSource,
                                "select author_id from qp_revision where post_id = ? order by id desc",
                                post.id().value())
                        .get(0))
                .isEqualTo(Long.toString(admin.value()));
        RevisionSnapshot newest = Identities.onSite(
                        site, Identities.user(editor), () -> revisions.ofPost(post.id(), RevisionQuery.all()))
                .get(0);
        clock.set(Instant.parse("2026-01-15T14:00:00Z"));
        PostSnapshot again =
                Identities.onSite(site, Identities.user(admin), () -> revisions.restore(newest.id(), Set.of()));
        assertThat(again.modifiedAt()).isEqualTo(Instant.parse("2026-01-15T14:00:00Z"));
        assertThat(rows(post.id())).hasSize(3);
    }

    @Test
    void aFieldSubsetRestoresOnlyThoseFields() throws Exception {
        PostSnapshot post = create(
                editor,
                PostChanges.builder().title("v1").content("one").excerpt("e1").status("publish"));
        update(
                editor,
                post.id(),
                PostChanges.builder().title("v2").content("two").excerpt("e2"));
        update(
                editor,
                post.id(),
                PostChanges.builder().title("v3").content("three").excerpt("e3"));
        RevisionSnapshot v2 = revisionTitled(post.id(), "v2");
        PostSnapshot restored = Identities.onSite(
                site, Identities.user(editor), () -> revisions.restore(v2.id(), EnumSet.of(RevisionField.TITLE)));
        assertThat(restored.title()).isEqualTo("v2");
        assertThat(restored.content()).isEqualTo("three");
        assertThat(restored.excerpt()).isEqualTo("e3");
        assertThat(rows(post.id())).containsExactly("v2|two", "v3|three", "v2|three");
        PostSnapshot full =
                Identities.onSite(site, Identities.user(editor), () -> revisions.restore(v2.id(), Set.of()));
        assertThat(full.content()).isEqualTo("two");
        assertThat(full.excerpt()).isEqualTo("e2");
    }

    @Test
    void aFloatingDraftRestoredIsDatedNow() throws Exception {
        PostSnapshot draft = create(editor, PostChanges.builder().title("v1").status("draft"));
        update(editor, draft.id(), PostChanges.builder().title("v2"));
        update(editor, draft.id(), PostChanges.builder().title("v3"));
        RevisionSnapshot v2 = revisionTitled(draft.id(), "v2");
        clock.set(Instant.parse("2026-01-16T09:00:00Z"));
        PostSnapshot restored =
                Identities.onSite(site, Identities.user(editor), () -> revisions.restore(v2.id(), Set.of()));
        assertThat(restored.publishedAt()).isEmpty();
        assertThat(restored.modifiedAt()).isEqualTo(Instant.parse("2026-01-16T09:00:00Z"));
        assertThat(restored.localDate(java.time.ZoneOffset.UTC))
                .isEqualTo(java.time.LocalDateTime.of(2026, 1, 16, 9, 0));
    }

    @Test
    void theRevisionsMetaReplacesThePostsAfterTheNewRevisionCopiedTheOldMeta() throws Exception {
        PostSnapshot post =
                create(editor, PostChanges.builder().title("v1").content("one").status("publish"));
        Identities.onSite(
                site, Identities.user(editor), () -> meta.update(post.id(), "footnotes", TextNode.valueOf("[0]")));
        update(editor, post.id(), PostChanges.builder().title("v2"));
        Identities.onSite(
                site, Identities.user(editor), () -> meta.update(post.id(), "footnotes", TextNode.valueOf("[1]")));
        update(editor, post.id(), PostChanges.builder().title("v3"));
        RevisionSnapshot v2 = revisionTitled(post.id(), "v2");
        assertThat(v2.meta("footnotes")).containsExactly(TextNode.valueOf("[0]"));
        PostSnapshot restored =
                Identities.onSite(site, Identities.user(editor), () -> revisions.restore(v2.id(), Set.of()));
        assertThat(restored.meta("footnotes")).containsExactly(TextNode.valueOf("[0]"));
        assertThat(Identities.onSite(site, Identities.user(editor), () -> meta.get(post.id(), "footnotes")))
                .containsExactly(TextNode.valueOf("[0]"));
        RevisionSnapshot created = Identities.onSite(
                        site, Identities.user(editor), () -> revisions.ofPost(post.id(), RevisionQuery.all()))
                .get(0);
        assertThat(created.title()).isEqualTo("v2");
        assertThat(created.meta("footnotes")).containsExactly(TextNode.valueOf("[1]"));
        // a key the revision lacks ends empty on the post
        RevisionSnapshot bare = Identities.onSite(site, Identities.user(editor), () -> {
            meta.delete(post.id(), "footnotes");
            posts.update(post.id(), PostChanges.builder().title("v4").build());
            return revisions.ofPost(post.id(), RevisionQuery.all()).get(0);
        });
        assertThat(bare.meta("footnotes")).isEmpty();
        Identities.onSite(
                site, Identities.user(editor), () -> meta.update(post.id(), "footnotes", TextNode.valueOf("[9]")));
        Identities.onSite(site, Identities.user(editor), () -> revisions.restore(bare.id(), Set.of()));
        assertThat(Identities.onSite(site, Identities.user(editor), () -> meta.get(post.id(), "footnotes")))
                .isEmpty();
        // the next save sees the meta change the restore made after its revision
        update(editor, post.id(), PostChanges.builder().title("v4"));
        assertThat(Identities.onSite(
                                site, Identities.user(editor), () -> revisions.ofPost(post.id(), RevisionQuery.all()))
                        .get(0)
                        .meta("footnotes"))
                .isEmpty();
    }

    @Test
    void aRestoreRefusedByTheUpdateForEmptyContentLeavesTheTransactionUsableAndChangesNothing() throws Exception {
        PostSnapshot post =
                create(editor, PostChanges.builder().title("v1").content("one").status("publish"));
        RevisionId blank =
                TestData.revision(dataSource, site, post.id(), admin, "", "", "", "{}", true, "2026-01-15T12:30:00Z");
        Identities.onSite(site, Identities.user(editor), () -> {
            assertThatThrownBy(() -> revisions.restore(blank, Set.of()))
                    .isInstanceOfSatisfying(
                            PostException.class,
                            refused -> assertThat(refused.code()).isEqualTo("empty_content"));
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            return null;
        });
        assertThat(Jdbc.strings(
                        dataSource,
                        "select title from qp_post where id = ?",
                        post.id().value()))
                .containsExactly("v1");
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_revision where post_id = ?",
                        post.id().value()))
                .isEqualTo(1);
    }

    @Test
    void restoringNeedsEnabledRevisionsUnlessTheRevisionIsAnAutosave() throws Exception {
        PostSnapshot post =
                create(editor, PostChanges.builder().title("v1").content("one").status("publish"));
        update(editor, post.id(), PostChanges.builder().title("v2"));
        RevisionSnapshot v2 = revisionTitled(post.id(), "v2");
        RevisionId autosave = TestData.revision(
                dataSource, site, post.id(), admin, "auto", "saved", "", "{}", true, "2026-01-15T12:30:00Z");
        TestData.featureOff(dataSource, site, "revisions");
        assertThatThrownBy(() ->
                        Identities.onSite(site, Identities.user(editor), () -> revisions.restore(v2.id(), Set.of())))
                .isInstanceOfSatisfying(
                        RevisionException.class,
                        refused -> assertThat(refused.code()).isEqualTo("revisions_not_enabled"));
        assertThat(Jdbc.strings(
                        dataSource,
                        "select title from qp_post where id = ?",
                        post.id().value()))
                .containsExactly("v2");
        PostSnapshot restored =
                Identities.onSite(site, Identities.user(editor), () -> revisions.restore(autosave, Set.of()));
        assertThat(restored.title()).isEqualTo("auto");
        assertThat(restored.content()).isEqualTo("saved");
        // revisions are off for the site: the restoring update saved no new revision
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_revision where post_id = ?",
                        post.id().value()))
                .isEqualTo(2);
    }

    @Test
    void theRestoredTextIsFilteredForTheRestorer() throws Exception {
        UserId contributor = TestData.profile(dataSource, "contributor");
        TestData.member(dataSource, TestData.MAIN, contributor, "[\"contributor\"]");
        String html = "<script>x()</script>kept";
        PostSnapshot pending = Identities.onSite(
                site,
                Identities.user(admin),
                () -> posts.create(PostChanges.builder()
                        .title("P")
                        .content(html)
                        .status("pending")
                        .author(contributor)
                        .build()));
        update(admin, pending.id(), PostChanges.builder().content(html + " more"));
        RevisionSnapshot withScript = revisionTitled(pending.id(), "P");
        assertThat(withScript.content()).startsWith("<script");
        PostSnapshot restored = Identities.onSite(
                site, Identities.user(contributor), () -> revisions.restore(withScript.id(), Set.of()));
        assertThat(restored.content()).isEqualTo("x()kept more");
    }

    @Test
    void aMissingOrForeignRevisionIsRefused() throws Exception {
        assertThatThrownBy(() -> Identities.onSite(
                        site, Identities.user(editor), () -> revisions.restore(RevisionId.of(999_999_999L), Set.of())))
                .isInstanceOfSatisfying(
                        RevisionException.class,
                        refused -> assertThat(refused.code()).isEqualTo("rest_post_invalid_id"))
                .hasMessage("Invalid revision ID.");
        SiteId other = TestData.site(dataSource);
        PostId theirs = TestData.post(dataSource, other, "post", "publish", "t", "T");
        RevisionId foreign =
                TestData.revision(dataSource, other, theirs, null, "x", "", "", "{}", false, "2026-01-15T12:00:00Z");
        assertThatThrownBy(() ->
                        Identities.onSite(site, Identities.user(editor), () -> revisions.restore(foreign, Set.of())))
                .isInstanceOfSatisfying(
                        RevisionException.class,
                        refused -> assertThat(refused.code()).isEqualTo("rest_post_invalid_id"));
    }

    @Test
    void anAuthorlessRevisionRestoresItsText() throws Exception {
        PostSnapshot post =
                create(editor, PostChanges.builder().title("v1").content("one").status("publish"));
        UserId gone = TestData.profile(dataSource, "gone");
        RevisionId orphan = TestData.revision(
                dataSource, site, post.id(), gone, "orphan", "lost author", "", "{}", false, "2026-01-15T11:00:00Z");
        Jdbc.update(dataSource, "delete from qp_user_profile where id = ?", gone.value());
        assertThat(Jdbc.strings(dataSource, "select count(author_id) from qp_revision where id = ?", orphan.value()))
                .containsExactly("0");
        clock.set(Instant.parse("2026-01-15T13:00:00Z"));
        PostSnapshot restored =
                Identities.onSite(site, Identities.user(editor), () -> revisions.restore(orphan, Set.of()));
        assertThat(restored.title()).isEqualTo("orphan");
        assertThat(restored.content()).isEqualTo("lost author");
        assertThat(restored.modifiedAt()).isEqualTo(Instant.parse("2026-01-15T13:00:00Z"));
    }
}
