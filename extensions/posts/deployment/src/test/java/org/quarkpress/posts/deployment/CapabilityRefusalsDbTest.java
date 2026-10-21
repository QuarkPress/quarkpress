package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.Arc;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.HeldLocks;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostMeta;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;

/**
 * A refused capability check leaves the caller's transaction usable: the caller can catch the refusal and go on to
 * commit what it does next.
 */
class CapabilityRefusalsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(CapabilityRefusalsDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    PostMeta meta;

    @Inject
    TransactionManager transactions;

    SiteId site;
    UserId subscriber;
    UserId contributor;
    UserId editor;

    @BeforeEach
    void setUp() {
        site = TestData.site(dataSource);
        subscriber = TestData.memberOfMain(dataSource, "subscriber");
        contributor = TestData.memberOfMain(dataSource, "contributor");
        editor = TestData.memberOfMain(dataSource, "editor");
    }

    private static void become(SecurityIdentity identity) {
        Arc.container().instance(CurrentIdentityAssociation.class).get().setIdentity(identity);
    }

    private String lacks(String capability) {
        return "the caller lacks the capability " + capability + " on site " + site.value();
    }

    private PostSnapshot create(UserId user, PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(user), () -> posts.create(changes.build()));
    }

    /**
     * In one transaction: {@code refused} as nobody is refused as unauthenticated, then as {@code refusedUser} as
     * forbidden, both for {@code capability}; the transaction stays active and {@code allowed} runs as an editor.
     */
    private void refusedThenAllowed(UserId refusedUser, String capability, ThrowingCallable refused, Runnable allowed)
            throws Exception {
        Identities.onSite(site, Identities.anonymous(), () -> {
            assertThatThrownBy(refused)
                    .isInstanceOf(UnauthorizedException.class)
                    .hasMessage(lacks(capability));
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            become(Identities.user(refusedUser));
            assertThatThrownBy(refused).isInstanceOf(ForbiddenException.class).hasMessage(lacks(capability));
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            become(Identities.user(editor));
            allowed.run();
            return null;
        });
    }

    private String column(String column, PostSnapshot post) {
        return Jdbc.strings(
                        dataSource,
                        "select " + column + " from qp_post where id = ?",
                        post.id().value())
                .get(0);
    }

    @Test
    void aRefusedCreateLeavesTheTransactionUsable() throws Exception {
        refusedThenAllowed(
                subscriber,
                "edit_posts",
                () -> posts.create(PostChanges.builder().title("Refused").build()),
                () -> posts.create(PostChanges.builder().title("Kept").build()));
        assertThat(Jdbc.strings(dataSource, "select title from qp_post where site_id = ?", site.value()))
                .containsExactly("Kept");
    }

    @Test
    void aRefusedUpdateLeavesTheTransactionUsable() throws Exception {
        PostSnapshot post = create(editor, PostChanges.builder().title("Theirs").status("publish"));
        refusedThenAllowed(
                contributor,
                "edit_post",
                () -> posts.update(
                        post.id(), PostChanges.builder().title("Refused").build()),
                () -> posts.update(
                        post.id(), PostChanges.builder().title("Kept").build()));
        assertThat(column("title", post)).isEqualTo("Kept");
    }

    @Test
    void aRefusedQueryLeavesTheTransactionUsable() throws Exception {
        PostSnapshot draft = create(editor, PostChanges.builder().title("Draft"));
        List<PostId> found = new ArrayList<>();
        refusedThenAllowed(
                subscriber,
                "edit_posts",
                () -> posts.query(PostQuery.builder().statuses("draft").build()),
                () -> posts.query(PostQuery.builder().statuses("draft").build()).posts().stream()
                        .map(PostSnapshot::id)
                        .forEach(found::add));
        assertThat(found).containsExactly(draft.id());
    }

    @Test
    void aRefusedPublishLeavesTheTransactionUsable() throws Exception {
        PostSnapshot post = create(editor, PostChanges.builder().title("Draft"));
        refusedThenAllowed(contributor, "publish_post", () -> posts.publish(post.id()), () -> posts.publish(post.id()));
        assertThat(column("status", post)).isEqualTo("publish");
    }

    @Test
    void aRefusedTrashLeavesTheTransactionUsable() throws Exception {
        PostSnapshot post = create(editor, PostChanges.builder().title("Theirs").status("publish"));
        refusedThenAllowed(contributor, "delete_post", () -> posts.trash(post.id()), () -> posts.trash(post.id()));
        assertThat(column("status", post)).isEqualTo("trash");
    }

    @Test
    void aRefusedUntrashLeavesTheTransactionUsable() throws Exception {
        PostSnapshot post = create(editor, PostChanges.builder().title("Theirs").status("publish"));
        Identities.onSite(site, Identities.user(editor), () -> posts.trash(post.id()));
        refusedThenAllowed(contributor, "delete_post", () -> posts.untrash(post.id()), () -> posts.untrash(post.id()));
        assertThat(column("status", post)).isEqualTo("draft");
    }

    @Test
    void aRefusedDeleteLeavesTheTransactionUsable() throws Exception {
        PostSnapshot post = create(editor, PostChanges.builder().title("Theirs").status("publish"));
        refusedThenAllowed(
                contributor, "delete_post", () -> posts.delete(post.id(), true), () -> posts.delete(post.id(), true));
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_post where id = ?",
                        post.id().value()))
                .isZero();
    }

    @Test
    void aRefusedAncestorsLeavesTheTransactionUsable() throws Exception {
        PostSnapshot top =
                create(editor, PostChanges.builder().type("page").title("Top").status("publish"));
        PostSnapshot hidden = create(
                editor, PostChanges.builder().type("page").title("Hidden").parent(top.id()));
        refusedThenAllowed(subscriber, "read_post", () -> posts.ancestors(hidden.id()), () -> {
            assertThat(posts.ancestors(hidden.id())).containsExactly(top.id());
            posts.update(hidden.id(), PostChanges.builder().title("Kept").build());
        });
        assertThat(column("title", hidden)).isEqualTo("Kept");
    }

    @Test
    void aRefusedUriLeavesTheTransactionUsable() throws Exception {
        PostSnapshot top =
                create(editor, PostChanges.builder().type("page").title("Top").status("publish"));
        PostSnapshot hidden = create(
                editor,
                PostChanges.builder()
                        .type("page")
                        .title("Hidden")
                        .slug("hidden")
                        .parent(top.id()));
        refusedThenAllowed(subscriber, "read_post", () -> posts.uri(hidden.id()), () -> {
            assertThat(posts.uri(hidden.id())).isEqualTo("top/hidden");
            posts.update(hidden.id(), PostChanges.builder().title("Kept").build());
        });
        assertThat(column("title", hidden)).isEqualTo("Kept");
    }

    @Test
    void aRefusedMetaReadLeavesTheTransactionUsable() throws Exception {
        PostSnapshot draft = create(editor, PostChanges.builder().title("Draft"));
        refusedThenAllowed(subscriber, "read_post", () -> meta.get(draft.id(), "color"), () -> {
            assertThat(meta.get(draft.id(), "color")).isEmpty();
            posts.update(draft.id(), PostChanges.builder().title("Kept").build());
        });
        refusedThenAllowed(subscriber, "read_post", () -> meta.all(draft.id()), () -> {
            assertThat(meta.all(draft.id())).doesNotContainKey("color");
            posts.update(draft.id(), PostChanges.builder().title("Kept again").build());
        });
        assertThat(column("title", draft)).isEqualTo("Kept again");
    }

    @Test
    void aRefusedMetaWriteLeavesTheTransactionUsable() throws Exception {
        PostSnapshot post = create(editor, PostChanges.builder().title("Theirs").status("publish"));
        refusedThenAllowed(
                contributor,
                "edit_post",
                () -> meta.add(post.id(), "color", TextNode.valueOf("refused"), false),
                () -> meta.add(post.id(), "color", TextNode.valueOf("kept"), false));
        refusedThenAllowed(
                contributor,
                "edit_post",
                () -> meta.update(post.id(), "color", TextNode.valueOf("refused")),
                () -> meta.update(post.id(), "size", TextNode.valueOf("kept")));
        refusedThenAllowed(
                contributor,
                "edit_post",
                () -> meta.delete(post.id(), "color"),
                () -> meta.delete(post.id(), "size", TextNode.valueOf("kept")));
        assertThat(Identities.onSite(site, Identities.user(editor), () -> meta.get(post.id(), "color")))
                .containsExactly(TextNode.valueOf("kept"));
        assertThat(Identities.onSite(site, Identities.user(editor), () -> meta.get(post.id(), "size")))
                .isEmpty();
    }

    @Test
    void aMissingPostOrAProtectedKeyLeavesTheTransactionUsableForMeta() throws Exception {
        PostSnapshot post = create(editor, PostChanges.builder().title("Post"));
        PostId missing = PostId.of(Long.MAX_VALUE);
        Identities.onSite(site, Identities.user(editor), () -> {
            List<ThrowingCallable> invalid = List.of(
                    () -> meta.get(missing, "color"),
                    () -> meta.all(missing),
                    () -> meta.add(missing, "color", TextNode.valueOf("x"), false),
                    () -> meta.update(missing, "color", TextNode.valueOf("x")),
                    () -> meta.delete(missing, "color"));
            for (ThrowingCallable call : invalid) {
                assertThatThrownBy(call)
                        .isInstanceOfSatisfying(
                                PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"));
                assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            }
            assertThatThrownBy(() -> meta.add(post.id(), "_hidden", TextNode.valueOf("x"), false))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("the meta key _hidden is protected");
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            posts.update(post.id(), PostChanges.builder().title("Kept").build());
            return null;
        });
        assertThat(column("title", post)).isEqualTo("Kept");
    }

    @Test
    void aLockRefusalStillMarksTheTransactionForRollback() throws Exception {
        UserId author = TestData.memberOfMain(dataSource, "author");
        SiteId spawning = TestData.site(dataSource);
        assertThatThrownBy(() -> Identities.onSite(spawning, Identities.system(), () -> {
                    HeldLocks.record("qp_user_profile:" + author.value());
                    return Identities.onSite(site, Identities.user(editor), () -> {
                        assertThatThrownBy(() -> posts.create(PostChanges.builder()
                                        .title("Held")
                                        .author(author)
                                        .build()))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("the profile of user " + author.value()
                                        + " is locked by a transaction that waits for this post write: write the"
                                        + " post in a separate transaction");
                        assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
                        throw new IllegalStateException("leave");
                    });
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("leave");
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post where site_id = ?", site.value()))
                .isZero();
    }
}
