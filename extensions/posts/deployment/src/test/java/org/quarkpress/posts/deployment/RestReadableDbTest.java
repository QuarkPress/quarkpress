package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;

/**
 * WordPress's REST read decision for posts and attachments: an attachment reads through its own effective status for
 * a logged-in caller and through its parent for anyone, so a trashed parent and a parent of a type not shown in REST
 * hide the attachment from anonymous callers only.
 */
class RestReadableDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(RestReadableDbTest.class, HiddenType.class);

    static final SiteId SITE = TestData.MAIN;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    PostPermissions permissions;

    UserId bobId;
    UserId carolId;
    UserId daveId;
    SecurityIdentity anonymous;
    SecurityIdentity erin;
    SecurityIdentity carol;
    SecurityIdentity dave;
    SecurityIdentity bob;

    @BeforeEach
    void setUp() {
        bobId = TestData.memberOfMain(dataSource, "editor");
        carolId = TestData.memberOfMain(dataSource, "author");
        daveId = TestData.memberOfMain(dataSource, "contributor");
        anonymous = Identities.anonymous();
        erin = Identities.user(TestData.memberOfMain(dataSource, "subscriber"));
        carol = Identities.user(carolId);
        dave = Identities.user(daveId);
        bob = Identities.user(bobId);
    }

    @Test
    void theAttachmentReadTable() throws Exception {
        // callers in order: anonymous, erin (subscriber), carol (author), dave (the attachments' author), bob (editor)
        assertThat(readers(attachment(Optional.empty(), "inherit"))).containsExactly(true, true, true, true, true);
        assertThat(readers(attachment(Optional.of(parent("post", "publish", bobId)), "inherit")))
                .containsExactly(true, true, true, true, true);
        assertThat(readers(attachment(Optional.of(parent("post", "draft", bobId)), "inherit")))
                .containsExactly(false, false, false, true, true);
        // an author reads through a parent she may edit
        assertThat(readers(attachment(Optional.of(parent("post", "draft", carolId)), "inherit")))
                .containsExactly(false, false, true, true, true);
        assertThat(readers(attachment(Optional.of(parent("post", "private", bobId)), "inherit")))
                .containsExactly(false, false, false, true, true);
        assertThat(readers(attachment(Optional.of(parent("post", "private", carolId)), "inherit")))
                .containsExactly(false, false, true, true, true);

        // the two rows where a logged-in caller reads what an anonymous one does not
        PostId trashed = parent("post", "publish", bobId);
        PostId ofTrashed = attachment(Optional.of(trashed), "inherit");
        as(bob, () -> posts.trash(trashed));
        assertThat(readers(ofTrashed)).containsExactly(false, true, true, true, true);
        assertThat(readers(attachment(Optional.of(parent("hidden", "publish", bobId)), "inherit")))
                .containsExactly(false, true, true, true, true);

        assertThat(readers(attachment(Optional.empty(), "private"))).containsExactly(false, false, false, true, true);
        PostId inTrash = attachment(Optional.empty(), "inherit");
        as(bob, () -> posts.trash(inTrash));
        assertThat(readers(inTrash)).containsExactly(false, false, false, true, true);
    }

    @Test
    void postsKeepTheirReadDecision() throws Exception {
        PostId draft = parent("post", "draft", bobId);
        assertThat(readable(carol, draft)).isFalse();
        assertThat(readable(bob, draft)).isTrue();
        PostId published = parent("post", "publish", bobId);
        assertThat(readable(anonymous, published)).isTrue();
    }

    @Test
    void anotherSitesPostIsNeverReadable() throws Exception {
        SiteId other = TestData.site(dataSource);
        PostId elsewhere = TestData.post(dataSource, other, "post", "publish", "", "Elsewhere");
        PostSnapshot snapshot = Identities.onSite(
                other, Identities.system(), () -> posts.find(elsewhere).orElseThrow());
        assertThat(Identities.onSite(other, Identities.system(), () -> permissions.restReadable(bob, snapshot)))
                .isTrue();
        // bound to another site than the post's, even a published post is refused, for every caller
        for (SecurityIdentity caller : List.of(anonymous, erin, bob, Identities.system())) {
            assertThat(Identities.onSite(SITE, Identities.system(), () -> permissions.restReadable(caller, snapshot)))
                    .isFalse();
        }
    }

    @Test
    void anAttachmentWhoseParentIsGoneReadsAsUnattached() throws Exception {
        // the stored parent cannot name a missing row here (a foreign key keeps it), so the lookup answers none
        PostId draft = parent("post", "draft", bobId);
        PostId attachment = attachment(Optional.of(draft), "inherit");
        List<Boolean> answers = new ArrayList<>();
        for (SecurityIdentity caller : List.of(anonymous, erin, carol, dave, bob)) {
            answers.add(Identities.onSite(
                    SITE,
                    Identities.system(),
                    () -> permissions.restReadable(
                            caller, posts.find(attachment).orElseThrow(), id -> Optional.empty())));
        }
        assertThat(answers).containsExactly(true, true, true, true, true);
    }

    @Test
    void theParentLookupIsAskedOncePerParent() throws Exception {
        PostId draft = parent("post", "draft", bobId);
        List<PostId> attachments = List.of(
                attachment(Optional.of(draft), "inherit"),
                attachment(Optional.of(draft), "inherit"),
                attachment(Optional.of(draft), "inherit"));
        List<PostId> asked = new ArrayList<>();
        Map<PostId, Optional<PostSnapshot>> memo = new HashMap<>();
        List<Boolean> answers = Identities.onSite(SITE, Identities.system(), () -> {
            Function<PostId, Optional<PostSnapshot>> parents = id -> memo.computeIfAbsent(id, key -> {
                asked.add(key);
                return posts.find(key);
            });
            List<Boolean> found = new ArrayList<>();
            for (PostId attachment : attachments) {
                PostSnapshot snapshot = posts.find(attachment).orElseThrow();
                found.add(permissions.restReadable(erin, snapshot, parents));
            }
            return found;
        });
        assertThat(answers).containsExactly(false, false, false);
        assertThat(asked).containsExactly(draft);
    }

    /** The decision for anonymous, erin, carol, dave and bob, in that order. */
    private List<Boolean> readers(PostId post) throws Exception {
        List<Boolean> answers = new ArrayList<>();
        for (SecurityIdentity caller : List.of(anonymous, erin, carol, dave, bob)) {
            answers.add(readable(caller, post));
        }
        return answers;
    }

    private boolean readable(SecurityIdentity caller, PostId post) throws Exception {
        return Identities.onSite(SITE, Identities.system(), () -> {
            PostSnapshot snapshot = posts.find(post).orElseThrow();
            return permissions.restReadable(caller, snapshot);
        });
    }

    private PostId parent(String type, String status, UserId author) throws Exception {
        return as(
                        bob,
                        () -> posts.create(PostChanges.builder()
                                .type(type)
                                .status(status)
                                .title("Parent")
                                .author(author)
                                .build()))
                .id();
    }

    /** An attachment by dave, made by bob (dave may not upload). */
    private PostId attachment(Optional<PostId> parent, String status) throws Exception {
        return as(bob, () -> {
                    PostChanges.Builder changes = PostChanges.builder()
                            .type("attachment")
                            .status(status)
                            .title("Picture")
                            .author(daveId);
                    parent.ifPresent(changes::parent);
                    return posts.create(changes.build());
                })
                .id();
    }

    private <T> T as(SecurityIdentity identity, Callable<T> work) throws Exception {
        return Identities.onSite(SITE, identity, work);
    }
}
