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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.revisions.RevisionException;
import org.quarkpress.revisions.Revisions;

/** Restoring needs the right to edit the post, as the revisions screen checks it. */
class RestoreCapabilityDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(RestoreCapabilityDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Revisions revisions;

    @Inject
    TransactionManager transactions;

    private SiteId site;
    private UserId administrator;
    private UserId editor;
    private UserId author;
    private UserId contributor;
    private PostId authorsPublished;
    private RevisionId revision;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
        administrator = member("administrator");
        editor = member("editor");
        author = member("author");
        contributor = member("contributor");
        authorsPublished = TestData.postBy(dataSource, site, author, "post", "publish", "Now");
        revision = TestData.revision(
                dataSource, site, authorsPublished, author, "Then", "then", "", "{}", false, "2026-01-15T12:00:00Z");
    }

    private UserId member(String role) {
        UserId user = TestData.profile(dataSource, role);
        TestData.member(dataSource, TestData.MAIN, user, "[\"" + role + "\"]");
        return user;
    }

    private String title() {
        return Jdbc.strings(dataSource, "select title from qp_post where id = ?", authorsPublished.value())
                .get(0);
    }

    @Test
    void whoMayEditThePostMayRestoreIt() throws Exception {
        assertThatThrownBy(() -> Identities.onSite(
                        site, Identities.user(contributor), () -> revisions.restore(revision, Set.of())))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() ->
                        Identities.onSite(site, Identities.anonymous(), () -> revisions.restore(revision, Set.of())))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(title()).isEqualTo("Now");
        for (UserId user : new UserId[] {author, editor, administrator}) {
            Jdbc.update(dataSource, "update qp_post set title = 'Now' where id = ?", authorsPublished.value());
            assertThat(Identities.onSite(site, Identities.user(user), () -> revisions.restore(revision, Set.of()))
                            .title())
                    .isEqualTo("Then");
        }
        Jdbc.update(dataSource, "update qp_post set title = 'Now' where id = ?", authorsPublished.value());
        assertThat(Identities.onSite(site, Identities.system(), () -> revisions.restore(revision, Set.of()))
                        .title())
                .isEqualTo("Then");
    }

    @Test
    void refusalsBeforeTheWriteLeaveTheTransactionUsable() throws Exception {
        Identities.onSite(site, Identities.user(contributor), () -> {
            assertThatThrownBy(() -> revisions.restore(revision, Set.of())).isInstanceOf(ForbiddenException.class);
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            assertThatThrownBy(() -> revisions.restore(RevisionId.of(999_999_999L), Set.of()))
                    .isInstanceOfSatisfying(
                            RevisionException.class,
                            refused -> assertThat(refused.code()).isEqualTo("rest_post_invalid_id"));
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            return null;
        });
        TestData.featureOff(dataSource, site, "revisions");
        Identities.onSite(site, Identities.user(editor), () -> {
            assertThatThrownBy(() -> revisions.restore(revision, Set.of()))
                    .isInstanceOfSatisfying(
                            RevisionException.class,
                            refused -> assertThat(refused.code()).isEqualTo("revisions_not_enabled"));
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            return null;
        });
        assertThat(title()).isEqualTo("Now");
    }
}
