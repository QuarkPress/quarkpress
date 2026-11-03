package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostLocks;
import org.quarkpress.posts.PostMeta;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.revisions.AutosaveChanges;
import org.quarkpress.revisions.AutosaveResult;
import org.quarkpress.revisions.Revisions;

/** The autosave branch follows the stored post's status and author and the editor's lock, as WordPress's does. */
class LockAwareAutosaveDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(LockAwareAutosaveDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    PostMeta meta;

    @Inject
    PostLocks locks;

    @Inject
    Revisions revisions;

    @Inject
    TestClock clock;

    private SiteId site;
    private UserId carol;
    private UserId bob;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
        carol = TestData.profile(dataSource, "carol");
        TestData.member(dataSource, TestData.MAIN, carol, "[\"author\"]");
        bob = TestData.profile(dataSource, "bob");
        TestData.member(dataSource, TestData.MAIN, bob, "[\"editor\"]");
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
    }

    private AutosaveResult autosave(UserId user, PostId post, AutosaveChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(user), () -> revisions.autosave(post, changes.build()));
    }

    private PostSnapshot create(UserId user, PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(user), () -> posts.create(changes.build()));
    }

    private String post(PostId post, String column) {
        return Jdbc.strings(dataSource, "select " + column + " from qp_post where id = ?", post.value())
                .get(0);
    }

    private long count(PostId post) {
        return Jdbc.count(dataSource, "select count(*) from qp_revision where post_id = ?", post.value());
    }

    @Test
    void theBranchFollowsStatusAuthorAndLock() throws Exception {
        PostId draft = TestData.postBy(dataSource, site, carol, "post", "draft", "Carol's draft");
        assertThat(autosave(carol, draft, AutosaveChanges.builder().content("one")))
                .isInstanceOf(AutosaveResult.Draft.class);
        assertThat(post(draft, "content")).isEqualTo("one");
        // bob holds a fresh lock: carol's own draft gets her autosave, the post stays
        Identities.onSite(site, Identities.user(bob), () -> locks.take(draft));
        assertThat(autosave(carol, draft, AutosaveChanges.builder().content("two")))
                .isInstanceOfSatisfying(AutosaveResult.Stored.class, stored -> {
                    assertThat(stored.autosave().author()).contains(carol);
                    assertThat(stored.autosave().content()).isEqualTo("two");
                });
        assertThat(post(draft, "content")).isEqualTo("one");
        // bob on carol's draft: his autosave, whatever the lock
        assertThat(autosave(bob, draft, AutosaveChanges.builder().content("bob")))
                .isInstanceOfSatisfying(
                        AutosaveResult.Stored.class,
                        stored -> assertThat(stored.autosave().author()).contains(bob));
        assertThat(post(draft, "content")).isEqualTo("one");
        // bob's lock expired: carol's draft again
        clock.set(Instant.parse("2026-01-15T12:02:31Z"));
        assertThat(autosave(carol, draft, AutosaveChanges.builder().content("three")))
                .isInstanceOf(AutosaveResult.Draft.class);
        assertThat(post(draft, "content")).isEqualTo("three");
        // carol's own lock never blocks her
        Identities.onSite(site, Identities.user(carol), () -> locks.takeOver(draft));
        assertThat(autosave(carol, draft, AutosaveChanges.builder().content("four")))
                .isInstanceOf(AutosaveResult.Draft.class);
        assertThat(post(draft, "content")).isEqualTo("four");
        assertThat(count(draft)).isEqualTo(2);
        for (String status : List.of("pending", "publish", "trash")) {
            PostId post = TestData.postBy(dataSource, site, carol, "post", status, "Carol's " + status);
            assertThat(autosave(carol, post, AutosaveChanges.builder().content("x")))
                    .isInstanceOf(AutosaveResult.Stored.class);
            assertThat(post(post, "content")).isEmpty();
        }
    }

    @Test
    void theOwnDraftBranchAppliesThePreparedFields() throws Exception {
        PostSnapshot auto = create(carol, PostChanges.builder().title("Auto").status("auto-draft"));
        Identities.onSite(
                site, Identities.user(carol), () -> meta.update(auto.id(), "footnotes", TextNode.valueOf("[1]")));
        AutosaveResult result = autosave(
                carol,
                auto.id(),
                AutosaveChanges.builder()
                        .post(PostChanges.builder().status("draft").slug("x").build())
                        .title("T")
                        .meta("footnotes", TextNode.valueOf("[2]")));
        assertThat(result).isInstanceOfSatisfying(AutosaveResult.Draft.class, updated -> {
            assertThat(updated.post().status()).isEqualTo("draft");
            assertThat(updated.post().title()).isEqualTo("T");
            assertThat(updated.post().meta("footnotes")).containsExactly(TextNode.valueOf("[1]"));
        });
        assertThat(post(auto.id(), "status")).isEqualTo("draft");
        assertThat(post(auto.id(), "title")).isEqualTo("T");
        assertThat(count(auto.id())).isZero();
        String slug = post(auto.id(), "slug");
        PostSnapshot expected = Identities.onSite(
                site,
                Identities.user(carol),
                () -> posts.update(auto.id(), PostChanges.builder().slug("x").build()));
        assertThat(slug).isEqualTo(expected.slug());
        // the given post fields are not applied on the autosave branch
        PostId published = TestData.postBy(dataSource, site, carol, "post", "publish", "P");
        assertThat(autosave(
                        carol,
                        published,
                        AutosaveChanges.builder()
                                .post(PostChanges.builder().status("draft").build())
                                .title("T2")))
                .isInstanceOf(AutosaveResult.Stored.class);
        assertThat(post(published, "status")).isEqualTo("publish");
        assertThat(post(published, "title")).isEqualTo("P");
    }

    @Test
    void metaSentMakesEveryRevisionedKeyCompared() throws Exception {
        PostSnapshot published = create(
                bob,
                PostChanges.builder().author(carol).title("T").content("Body").status("publish"));
        AutosaveChanges.Builder identical = AutosaveChanges.builder()
                .title("T")
                .content("Body")
                .excerpt("")
                .meta("footnotes", TextNode.valueOf(""));
        assertThat(((AutosaveResult.Stored) autosave(carol, published.id(), identical)).written())
                .isTrue();
        clock.set(Instant.parse("2026-01-15T12:01:00Z"));
        AutosaveResult second = autosave(carol, published.id(), identical);
        assertThat(second).isInstanceOfSatisfying(AutosaveResult.Stored.class, stored -> {
            assertThat(stored.written()).isTrue();
            assertThat(stored.autosave().createdAt()).isEqualTo(Instant.parse("2026-01-15T12:01:00Z"));
        });
        clock.set(Instant.parse("2026-01-15T12:02:00Z"));
        AutosaveResult third = autosave(carol, published.id(), identical);
        assertThat(third).isInstanceOfSatisfying(AutosaveResult.Stored.class, stored -> {
            assertThat(stored.written()).isTrue();
            assertThat(stored.autosave().createdAt()).isEqualTo(Instant.parse("2026-01-15T12:02:00Z"));
        });
        // a body without meta compares the text only
        assertThat(((AutosaveResult.Stored) autosave(
                                carol,
                                published.id(),
                                AutosaveChanges.builder().title("T").content("Body")))
                        .written())
                .isFalse();
        // with the row stored, even empty, an identical autosave stays untouched
        Identities.onSite(
                site, Identities.user(bob), () -> meta.update(published.id(), "footnotes", TextNode.valueOf("")));
        clock.set(Instant.parse("2026-01-15T12:03:00Z"));
        AutosaveResult fourth = autosave(carol, published.id(), identical);
        assertThat(fourth).isInstanceOfSatisfying(AutosaveResult.Stored.class, stored -> {
            assertThat(stored.written()).isFalse();
            assertThat(stored.autosave().createdAt()).isEqualTo(Instant.parse("2026-01-15T12:02:00Z"));
        });
        assertThat(count(published.id())).isEqualTo(1);
    }

    @Test
    void concurrentAutosaveRefreshAndUpdateNeverDeadlock() throws Exception {
        PostSnapshot draft =
                create(carol, PostChanges.builder().title("D").content("start").status("draft"));
        PostId post = draft.id();
        Identities.onSite(site, Identities.user(bob), () -> locks.take(post));
        for (int round = 0; round < 50; round++) {
            String carols = "carol " + round;
            String bobs = "bob " + round;
            List<Object> results = Races.run(List.<Callable<Object>>of(
                    () -> Identities.onSite(
                            site,
                            Identities.user(carol),
                            () -> revisions.autosave(
                                    post,
                                    AutosaveChanges.builder().content(carols).build())),
                    () -> Identities.onSite(site, Identities.user(bob), () -> locks.refresh(post)),
                    () -> Identities.onSite(
                            site,
                            Identities.user(bob),
                            () -> posts.update(
                                    post, PostChanges.builder().content(bobs).build()))));
            List<String> failures = new ArrayList<>();
            for (Object result : results) {
                if (result instanceof Exception failure) {
                    failures.add(Failures.messages(failure));
                }
            }
            assertThat(failures).as("round " + round).isEmpty();
            assertThat(results.get(0)).isInstanceOfSatisfying(AutosaveResult.Stored.class, stored -> {
                assertThat(stored.autosave().author()).contains(carol);
                assertThat(stored.autosave().content()).isEqualTo(carols);
            });
            assertThat(results.get(1)).isInstanceOf(PostLocks.Take.Taken.class);
            assertThat(post(post, "content")).isEqualTo(bobs);
        }
        assertThat(Jdbc.strings(
                        dataSource,
                        "select content from qp_revision where post_id = ? and autosave = true",
                        post.value()))
                .containsExactly("carol 49");
    }
}
