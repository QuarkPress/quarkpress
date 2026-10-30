package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostRepository;

/** The post permission facade answers WordPress's capability checks for the current or a given identity. */
class PostPermissionsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(PostPermissionsDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    PostPermissions permissions;

    @Inject
    PostRepository posts;

    @Inject
    PostTypes types;

    @Inject
    PostStatuses statuses;

    @Inject
    TransactionManager transactions;

    SecurityIdentity administrator;
    SecurityIdentity editor;
    SecurityIdentity carol;
    SecurityIdentity dave;
    SecurityIdentity erin;
    SecurityIdentity anonymous;
    UserId daveId;
    PostSnapshot carolsPublished;
    PostSnapshot carolsDraft;
    PostSnapshot carolsPrivate;

    @BeforeEach
    void members() throws Exception {
        administrator = Identities.user(TestData.memberOfMain(dataSource, "administrator"));
        editor = Identities.user(TestData.memberOfMain(dataSource, "editor"));
        UserId carolId = TestData.memberOfMain(dataSource, "author");
        carol = Identities.user(carolId);
        daveId = TestData.memberOfMain(dataSource, "contributor");
        dave = Identities.user(daveId);
        erin = Identities.user(TestData.memberOfMain(dataSource, "subscriber"));
        anonymous = Identities.anonymous();
        carolsPublished = postBy(carolId, "publish");
        carolsDraft = postBy(carolId, "draft");
        carolsPrivate = postBy(carolId, "private");
    }

    private PostSnapshot postBy(UserId author, String status) throws Exception {
        PostId post = TestData.post(dataSource, TestData.MAIN, "post", status, "", status + " by carol");
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", author.value(), post.value());
        return Identities.onSite(
                TestData.MAIN,
                Identities.system(),
                () -> posts.findPost(TestData.MAIN, post)
                        .map(PostEntity::toSnapshot)
                        .orElseThrow());
    }

    private static <T> T as(SecurityIdentity identity, Callable<T> work) throws Exception {
        return Identities.onSite(TestData.MAIN, identity, work);
    }

    @Test
    void metaCapabilitiesFollowTheFacts() throws Exception {
        assertThat(as(editor, () -> permissions.can("edit_post", carolsPublished.id())))
                .isTrue();
        assertThat(as(dave, () -> permissions.can("edit_post", carolsPublished.id())))
                .isFalse();
        assertThat(as(carol, () -> permissions.can("edit_post", carolsDraft))).isTrue();
        assertThat(as(editor, () -> permissions.can("read_post", PostId.of(987_654))))
                .isFalse();
        assertThat(as(editor, () -> permissions.can("read_post", carolsPrivate.id())))
                .isTrue();
        assertThat(as(dave, () -> permissions.can("read_post", carolsPrivate.id())))
                .isFalse();
        // current_user_can: a logged-out visitor holds no read capability. That anyone may read a public post through
        // REST is the controllers' status rule (Acting.requireReadable, postTermsAccess's "viewable"), not read_post.
        assertThat(as(anonymous, () -> permissions.can("read_post", carolsPublished.id())))
                .isFalse();
    }

    @Test
    void anotherSitesPostIsCheckedAsMissing() throws Exception {
        SiteId other = TestData.site(dataSource);
        PostId foreign = TestData.post(dataSource, other, "post", "draft", "", "elsewhere");
        PostSnapshot snapshot = Identities.onSite(
                other,
                Identities.system(),
                () -> posts.findPost(other, foreign).map(PostEntity::toSnapshot).orElseThrow());
        for (String capability : List.of("edit_post", "delete_post", "read_post")) {
            assertThat(as(administrator, () -> permissions.can(capability, snapshot)))
                    .as(capability)
                    .isFalse();
            assertThat(as(editor, () -> permissions.can(capability, snapshot)))
                    .as(capability)
                    .isFalse();
            assertThat(as(editor, () -> permissions.can(capability, foreign)))
                    .as(capability)
                    .isFalse();
        }
        // the same post asked for its own site is answered from the facts
        assertThat(Identities.onSite(other, Identities.system(), () -> permissions.can("edit_post", snapshot)))
                .isTrue();
    }

    @Test
    void typeCapabilitiesAreMappedThroughTheTypesNames() throws Exception {
        assertThat(as(dave, () -> permissions.canCreate("post"))).isTrue();
        assertThat(as(dave, () -> permissions.canPublish("post"))).isFalse();
        assertThat(as(carol, () -> permissions.canPublish("post"))).isTrue();
        assertThat(as(carol, () -> permissions.canEditOthers("post"))).isFalse();
        assertThat(as(editor, () -> permissions.canType("edit_others_posts", "page")))
                .isTrue();
        assertThat(as(carol, () -> permissions.canType("edit_others_posts", "page")))
                .isFalse();
        assertThat(as(editor, () -> permissions.canType("edit_posts", "no_such_type")))
                .isFalse();
    }

    @Test
    void anUnknownGenericCapabilityIsRefused() throws Exception {
        assertThatThrownBy(() -> as(editor, () -> permissions.canType("edit_post", "post")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a generic post type capability: edit_post");
    }

    @Test
    void restTypeChecks() throws Exception {
        assertThat(as(erin, () -> permissions.canEditSomeRestType())).isFalse();
        assertThat(as(anonymous, () -> permissions.canEditSomeRestType())).isFalse();
        assertThat(as(dave, () -> permissions.canEditSomeRestType())).isTrue();
        assertThat(as(dave, () -> permissions.canEditSomeRestTypeSupporting("author")))
                .isTrue();
        assertThat(as(dave, () -> permissions.canEditSomeRestTypeSupporting("no-such-feature")))
                .isFalse();
    }

    @Test
    void statusVisibilityIsWordPresssRestRule() throws Exception {
        assertThat(as(anonymous, () -> permissions.statusVisible("publish"))).isTrue();
        assertThat(as(erin, () -> permissions.statusVisible("draft"))).isFalse();
        assertThat(as(erin, () -> permissions.statusVisible("trash"))).isFalse();
        assertThat(as(
                        dave,
                        () -> List.of("draft", "pending", "future", "private", "trash").stream()
                                .allMatch(permissions::statusVisible)))
                .isTrue();
        for (String internal : List.of("auto-draft", "inherit", "request-pending", "no-such-status")) {
            assertThat(as(administrator, () -> permissions.statusVisible(internal)))
                    .as(internal)
                    .isFalse();
        }
        assertThat(permissions.restStatuses())
                .containsExactly("publish", "future", "draft", "pending", "private", "trash");
    }

    @Test
    void typeChecksWorkWithoutATransaction() throws Exception {
        assertThat(as(
                        dave,
                        () -> QuarkusTransaction.suspendingExisting().call(() -> {
                            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
                            return permissions.canType("edit_posts", "post")
                                    && permissions.canEditSomeRestType()
                                    && permissions.statusVisible("draft")
                                    && !permissions.canPublish("post");
                        })))
                .isTrue();
    }

    @Test
    void viewableIsWordPresssDefinition() {
        assertThat(types.all().stream().filter(PostPermissions::typeViewable).map(PostTypeDefinition::key))
                .containsExactly("post", "page", "attachment");
        assertThat(PostPermissions.typeViewable(PostTypeDefinition.builder("queried")
                        .publiclyQueryable(true)
                        .build()))
                .isTrue();
        assertThat(PostPermissions.typeViewable(PostTypeDefinition.builder("unqueried")
                        .publicly(true)
                        .publiclyQueryable(false)
                        .build()))
                .isFalse();
        assertThat(statuses.all().stream()
                        .filter(PostPermissions::statusViewable)
                        .map(PostStatusDefinition::name))
                .containsExactly("publish");
        assertThat(PostPermissions.statusViewable(PostStatusDefinition.builder("shown")
                        .publicly(true)
                        .publiclyQueryable(true)
                        .build()))
                .isTrue();
        assertThat(PostPermissions.statusViewable(PostStatusDefinition.builder("unqueried")
                        .publicly(true)
                        .publiclyQueryable(false)
                        .build()))
                .isFalse();
        assertThat(PostPermissions.statusViewable(PostStatusDefinition.builder("guarded")
                        .protectedStatus(true)
                        .publiclyQueryable(true)
                        .build()))
                .isFalse();
        assertThat(PostPermissions.statusViewable(PostStatusDefinition.builder("hidden")
                        .internal(true)
                        .publiclyQueryable(true)
                        .build()))
                .isFalse();
    }

    @Test
    void anExplicitIdentityIsUsedInsteadOfTheCurrentOne() throws Exception {
        assertThat(as(erin, () -> permissions.canCreate(Identities.user(daveId), "post")))
                .isTrue();
        assertThat(as(dave, () -> permissions.canCreate(erin, "post"))).isFalse();
        assertThat(as(erin, () -> permissions.can(editor, "edit_post", carolsPublished.id())))
                .isTrue();
        assertThat(as(erin, () -> permissions.statusVisible(dave, "draft"))).isTrue();
    }
}
