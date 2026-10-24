package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.revisions.Revisions;

/** Nobody but the system deletes a revision: deleting one is forbidden to every user, as in WordPress. */
class DeleteCapabilityDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(DeleteCapabilityDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Revisions revisions;

    @Inject
    TransactionManager transactions;

    @Test
    void everyUserIsRefusedAndNothingIsDeleted() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId administrator = TestData.profile(dataSource, "administrator");
        TestData.member(dataSource, TestData.MAIN, administrator, "[\"administrator\"]");
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        UserId author = TestData.profile(dataSource, "author");
        TestData.member(dataSource, TestData.MAIN, author, "[\"author\"]");
        PostId post = TestData.postBy(dataSource, site, author, "post", "publish", "Mine");
        RevisionId revision =
                TestData.revision(dataSource, site, post, author, "r", "", "", "{}", false, "2026-01-15T12:00:00Z");
        for (UserId user : new UserId[] {administrator, editor, author}) {
            assertThatThrownBy(() -> Identities.onSite(site, Identities.user(user), () -> revisions.delete(revision)))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("delete_post");
        }
        assertThatThrownBy(() -> Identities.onSite(site, Identities.anonymous(), () -> revisions.delete(revision)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("delete_post");
        // a missing revision is refused the same way for a user, before any lookup
        assertThatThrownBy(() -> Identities.onSite(
                        site, Identities.user(administrator), () -> revisions.delete(RevisionId.of(999_999_999L))))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("delete_post");
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_revision where post_id = ?", post.value()))
                .isEqualTo(1);
        Identities.onSite(site, Identities.user(administrator), () -> {
            assertThatThrownBy(() -> revisions.delete(revision))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("delete_post");
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            return null;
        });
    }
}
