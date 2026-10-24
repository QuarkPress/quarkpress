package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostMeta;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostType;
import org.quarkpress.posts.Posts;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.revisions.Revisions;

/** A revision follows every update-like save of a post, as WordPress saves one after the post is written. */
class SaveDbTest {

    /** Autosaved (it has an editor) but not revisioned. */
    @PostType(
            key = "card",
            supports = {"title", "editor"})
    static class Card {}

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(SaveDbTest.class, Card.class);

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

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

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

    private PostSnapshot create(PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(editor), () -> posts.create(changes.build()));
    }

    private PostSnapshot update(UserId user, PostId post, PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(user), () -> posts.update(post, changes.build()));
    }

    /** The post's revisions oldest first as {@code title|content|excerpt|kind|author|createdAt}. */
    private List<String> rows(PostId post) {
        return Jdbc.strings(
                dataSource,
                "select concat(title, '|', content, '|', excerpt, '|', case when autosave then 'auto' else 'regular'"
                        + " end, '|', coalesce(author_id, 0), '|', " + TestDb.utcText("created_at")
                        + ") from qp_revision where post_id = ? order by created_at, id",
                post.value());
    }

    private long count(PostId post) {
        return Jdbc.count(dataSource, "select count(*) from qp_revision where post_id = ?", post.value());
    }

    @Test
    void anInsertSavesNothingAndTheFirstUpdateSavesThePostUpdateState() throws Exception {
        PostSnapshot post =
                create(PostChanges.builder().title("v1").content("one").status("publish"));
        assertThat(count(post.id())).isZero();
        clock.set(Instant.parse("2026-01-15T12:01:00Z"));
        PostSnapshot v2 = update(admin, post.id(), PostChanges.builder().title("v2"));
        assertThat(rows(post.id())).hasSize(1);
        String row = rows(post.id()).get(0);
        assertThat(row).startsWith("v2|one||regular|" + admin.value() + "|");
        assertThat(row).contains("2026-01-15 12:01:00");
        assertThat(v2.modifiedAt()).isEqualTo(Instant.parse("2026-01-15T12:01:00Z"));
        clock.set(Instant.parse("2026-01-15T12:02:00Z"));
        update(editor, post.id(), PostChanges.builder().title("v3"));
        assertThat(rows(post.id()))
                .hasSize(2)
                .anySatisfy(r -> assertThat(r).startsWith("v3|one||regular|" + editor.value()));
    }

    @Test
    void trashUntrashAndPublishSaveRevisionsWhenTheFieldsChanged() throws Exception {
        PostSnapshot post =
                create(PostChanges.builder().title("Keep").content("c").status("publish"));
        Identities.onSite(site, Identities.user(editor), () -> posts.trash(post.id()));
        assertThat(count(post.id())).isEqualTo(1);
        Identities.onSite(site, Identities.user(editor), () -> posts.untrash(post.id()));
        // the fields did not change since the revision the trash saved: nothing new
        assertThat(count(post.id())).isEqualTo(1);
        PostSnapshot future = create(PostChanges.builder()
                .title("Later")
                .content("c")
                .status("future")
                .dateGmt(Instant.parse("2090-01-01T00:00:00Z")));
        assertThat(count(future.id())).isZero();
        Identities.onSite(site, Identities.system(), () -> posts.publish(future.id()));
        assertThat(rows(future.id())).hasSize(1);
        // published by the system: no author. Publishing does not touch the post's modification time, which an insert
        // sets to the post's date (the scheduled day), so that is the revision's time
        assertThat(rows(future.id()).get(0)).startsWith("Later|c||regular|0|");
        assertThat(rows(future.id()).get(0)).endsWith("|2090-01-01 00:00:00");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select " + TestDb.utcText("modified_at") + " from qp_post where id = ?",
                        future.id().value()))
                .containsExactly("2090-01-01 00:00:00");
        Identities.onSite(site, Identities.system(), () -> posts.publish(future.id()));
        assertThat(count(future.id())).isEqualTo(1);
    }

    @Test
    void autoDraftsUnsupportedTypesAndSitesWithoutTheFeatureGetNone() throws Exception {
        PostSnapshot auto = create(PostChanges.builder().title("Auto").status("auto-draft"));
        update(editor, auto.id(), PostChanges.builder().title("Auto 2"));
        assertThat(count(auto.id())).isZero();
        PostSnapshot card =
                create(PostChanges.builder().type("card").title("Card").status("publish"));
        update(editor, card.id(), PostChanges.builder().title("Card 2"));
        assertThat(count(card.id())).isZero();
        PostSnapshot page =
                create(PostChanges.builder().type("page").title("Page").status("publish"));
        update(editor, page.id(), PostChanges.builder().title("Page 2"));
        assertThat(count(page.id())).isEqualTo(1);
        TestData.featureOff(dataSource, site, "revisions");
        update(editor, page.id(), PostChanges.builder().title("Page 3"));
        assertThat(count(page.id())).isEqualTo(1);
    }

    @Test
    void whitespaceOnlyEditsCreateNoRevision() throws Exception {
        PostSnapshot post =
                create(PostChanges.builder().title("T").content("a b\nc").status("publish"));
        update(editor, post.id(), PostChanges.builder().content("a b\nc"));
        assertThat(count(post.id())).isEqualTo(1);
        // a carriage return before the line feed, a trailing space, a tab for the space, a run of blank lines
        update(editor, post.id(), PostChanges.builder().content("a b\r\nc"));
        assertThat(count(post.id())).isEqualTo(1);
        update(editor, post.id(), PostChanges.builder().content("a b\nc "));
        assertThat(count(post.id())).isEqualTo(1);
        update(editor, post.id(), PostChanges.builder().content("a\tb\nc"));
        assertThat(count(post.id())).isEqualTo(1);
        update(editor, post.id(), PostChanges.builder().content("a  b\n\n\nc"));
        assertThat(count(post.id())).isEqualTo(1);
        update(editor, post.id(), PostChanges.builder().status("draft"));
        update(editor, post.id(), PostChanges.builder().slug("other"));
        assertThat(count(post.id())).isEqualTo(1);
        // a no-break space is not a space
        update(editor, post.id(), PostChanges.builder().content("a" + cp(0xA0) + "b\nc"));
        assertThat(count(post.id())).isEqualTo(2);
        update(editor, post.id(), PostChanges.builder().content("a b\nc"));
        assertThat(count(post.id())).isEqualTo(3);
        // a space after a line feed is kept
        update(editor, post.id(), PostChanges.builder().content("a b\n c"));
        assertThat(count(post.id())).isEqualTo(4);
    }

    @Test
    void aRevisionedMetaChangeAloneSavesARevisionThatCarriesTheMeta() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("T").content("c").status("publish"));
        update(editor, post.id(), PostChanges.builder().title("T2"));
        assertThat(count(post.id())).isEqualTo(1);
        Identities.onSite(
                site,
                Identities.user(editor),
                () -> meta.update(post.id(), "footnotes", TextNode.valueOf("[{\"id\":\"a\",\"content\":\"x\"}]")));
        Identities.onSite(
                site, Identities.user(editor), () -> meta.update(post.id(), "color", TextNode.valueOf("red")));
        // the meta was written after the save: it is not in that save's revision, the next save sees the change
        assertThat(count(post.id())).isEqualTo(1);
        update(editor, post.id(), PostChanges.builder().title("T2"));
        assertThat(count(post.id())).isEqualTo(2);
        assertThat(Jdbc.strings(
                        dataSource,
                        "select meta from qp_revision where post_id = ? order by id",
                        post.id().value()))
                .containsExactly("{}", "{\"footnotes\":[\"[{\\\"id\\\":\\\"a\\\",\\\"content\\\":\\\"x\\\"}]\"]}");
        update(editor, post.id(), PostChanges.builder().title("T2"));
        assertThat(count(post.id())).isEqualTo(2);
    }

    @Test
    void aPostWhoseOnlyRevisionsAreAutosavesIsAlwaysRevisioned() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("T").content("c").status("publish"));
        TestData.revision(dataSource, site, post.id(), editor, "T", "c", "", "{}", true, "2026-01-15T11:00:00Z");
        update(editor, post.id(), PostChanges.builder().title("T"));
        assertThat(count(post.id())).isEqualTo(2);
        update(editor, post.id(), PostChanges.builder().title("T"));
        assertThat(count(post.id())).isEqualTo(2);
    }

    @Test
    void theCopyIsFilteredForTheCallerWhoSaves() throws Exception {
        String html = "<script>x()</script>kept";
        PostSnapshot post = Identities.onSite(
                site,
                Identities.user(admin),
                () -> posts.create(PostChanges.builder()
                        .title("T")
                        .content(html)
                        .status("future")
                        .dateGmt(Instant.parse("2090-01-01T00:00:00Z"))
                        .build()));
        assertThat(post.content()).isEqualTo(html);
        Identities.onSite(site, Identities.system(), () -> posts.publish(post.id()));
        // the system saves filtered content (no unfiltered_html): kses drops the tags and keeps the text, as in
        // WordPress's cron; the post keeps the administrator's
        assertThat(Jdbc.strings(
                        dataSource,
                        "select content from qp_revision where post_id = ?",
                        post.id().value()))
                .containsExactly("x()kept");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select content from qp_post where id = ?",
                        post.id().value()))
                .containsExactly(html);
    }

    @Test
    void saveOnDemandFollowsTheSameRules() throws Exception {
        PostSnapshot post = create(PostChanges.builder().title("T").content("c").status("publish"));
        Identities.onSite(
                site, Identities.user(editor), () -> meta.update(post.id(), "footnotes", TextNode.valueOf("[1]")));
        RevisionSnapshot saved = Identities.onSite(site, Identities.user(editor), () -> revisions.save(post.id()))
                .orElseThrow();
        assertThat(saved.post()).isEqualTo(post.id());
        assertThat(saved.author()).contains(editor);
        assertThat(saved.title()).isEqualTo("T");
        assertThat(saved.meta("footnotes")).containsExactly(TextNode.valueOf("[1]"));
        assertThat(saved.autosave()).isFalse();
        assertThat(saved.createdAt()).isEqualTo(post.modifiedAt());
        assertThat(Identities.onSite(site, Identities.user(editor), () -> revisions.save(post.id())))
                .isEmpty();
        PostSnapshot auto = create(PostChanges.builder().title("A").status("auto-draft"));
        assertThat(Identities.onSite(site, Identities.user(editor), () -> revisions.save(auto.id())))
                .isEmpty();
        assertThat(count(auto.id())).isZero();
    }
}
