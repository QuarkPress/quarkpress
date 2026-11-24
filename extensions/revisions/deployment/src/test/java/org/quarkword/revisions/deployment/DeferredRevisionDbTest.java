package org.quarkword.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.core.UserId;
import org.quarkword.posts.MetaKeyDefinition;
import org.quarkword.posts.MetaWrite;
import org.quarkword.posts.PostChanges;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.Posts;
import org.quarkword.posts.RegisteredMeta;
import org.quarkword.revisions.RevisionQuery;
import org.quarkword.revisions.RevisionSnapshot;
import org.quarkword.revisions.Revisions;

/** A REST update's revision is made after the work, so it holds the meta written in the same request. */
class DeferredRevisionDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(DeferredRevisionDbTest.class);

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    RegisteredMeta registeredMeta;

    @Inject
    Revisions revisions;

    @Inject
    TestClock clock;

    private SiteId site;
    private UserId editor;

    @BeforeEach
    void newSite() {
        site = TestData.MAIN;
        editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
    }

    private MetaKeyDefinition footnotes() {
        return registeredMeta.keys("post").stream()
                .filter(key -> key.key().equals("footnotes"))
                .findFirst()
                .orElseThrow();
    }

    private List<RevisionSnapshot> rows(PostId post) throws Exception {
        return Identities.onSite(site, Identities.user(editor), () -> revisions.ofPost(post, RevisionQuery.all()));
    }

    @Test
    void oneRevisionIsMadeAfterTheWorkWithTheMetaWrittenInside() throws Exception {
        PostSnapshot post = Identities.onSite(
                site,
                Identities.user(editor),
                () -> posts.create(PostChanges.builder()
                        .title("v1")
                        .content("one")
                        .status("publish")
                        .build()));
        clock.set(Instant.parse("2026-01-15T12:01:00Z"));
        PostSnapshot updated = Identities.onSite(
                site,
                Identities.user(editor),
                () -> revisions.revisingAfter(post.id(), () -> {
                    PostSnapshot saved = posts.update(
                            post.id(),
                            PostChanges.builder().title("v2").content("two").build());
                    assertThat(registeredMeta.updateSingle(
                                    post.id(), footnotes(), TextNode.valueOf("[{\"id\":\"a\",\"content\":\"n\"}]")))
                            .isEqualTo(MetaWrite.WRITTEN);
                    return saved;
                }));
        assertThat(updated.title()).isEqualTo("v2");
        List<RevisionSnapshot> rows = rows(post.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).title()).isEqualTo("v2");
        assertThat(rows.get(0).content()).isEqualTo("two");
        assertThat(rows.get(0).meta("footnotes"))
                .containsExactly(TextNode.valueOf("[{\"id\":\"a\",\"content\":\"n\"}]"));
        assertThat(rows.get(0).author()).contains(editor);
        assertThat(rows.get(0).createdAt()).isEqualTo(Instant.parse("2026-01-15T12:01:00Z"));
        // an unchanged post makes no revision; a nested call saves once
        assertThat(Identities.onSite(
                        site,
                        Identities.user(editor),
                        () -> revisions.revisingAfter(
                                post.id(),
                                () -> revisions.revisingAfter(
                                        post.id(),
                                        () -> posts.update(
                                                post.id(),
                                                PostChanges.builder()
                                                        .content("two")
                                                        .build())))))
                .isNotNull();
        assertThat(rows(post.id())).hasSize(1);
        clock.set(Instant.parse("2026-01-15T12:02:00Z"));
        Identities.onSite(
                site,
                Identities.user(editor),
                () -> revisions.revisingAfter(
                        post.id(),
                        () -> revisions.revisingAfter(
                                post.id(),
                                () -> posts.update(
                                        post.id(),
                                        PostChanges.builder().content("three").build()))));
        assertThat(rows(post.id())).hasSize(2);
    }

    @Test
    void failedWorkMakesNoRevision() throws Exception {
        PostSnapshot post = Identities.onSite(
                site,
                Identities.user(editor),
                () -> posts.create(PostChanges.builder()
                        .title("v1")
                        .content("one")
                        .status("publish")
                        .build()));
        // an input refusal after the update keeps the transaction usable: the update stays, the revision is not made
        // (observed inside the transaction, which the scope would roll back if the refusal escaped it)
        List<String> seen = Identities.onSite(site, Identities.user(editor), () -> {
            assertThatThrownBy(() -> revisions.revisingAfter(post.id(), () -> {
                        posts.update(
                                post.id(), PostChanges.builder().title("v2").build());
                        throw new IllegalArgumentException("work refused");
                    }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("work refused");
            return List.of(
                    posts.find(post.id()).orElseThrow().title(),
                    Integer.toString(
                            revisions.ofPost(post.id(), RevisionQuery.all()).size()));
        });
        assertThat(seen).containsExactly("v2", "0");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select title from qw_post where id = ?",
                        post.id().value()))
                .containsExactly("v2");
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qw_revision where post_id = ?",
                        post.id().value()))
                .isZero();
        // a failure that rolls the transaction back leaves neither
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.user(editor),
                        () -> revisions.revisingAfter(post.id(), () -> {
                            posts.update(
                                    post.id(), PostChanges.builder().title("v3").build());
                            throw new IllegalStateException("work failed");
                        })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("work failed");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select title from qw_post where id = ?",
                        post.id().value()))
                .containsExactly("v2");
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qw_revision where post_id = ?",
                        post.id().value()))
                .isZero();
    }
}
