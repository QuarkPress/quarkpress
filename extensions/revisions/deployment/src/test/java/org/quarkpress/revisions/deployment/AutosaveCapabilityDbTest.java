package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.revisions.AutosaveChanges;
import org.quarkpress.revisions.AutosaveResult;
import org.quarkpress.revisions.RevisionException;
import org.quarkpress.revisions.Revisions;

/** Autosaving needs the right to edit the post, as the REST endpoint checks it. */
class AutosaveCapabilityDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(AutosaveCapabilityDbTest.class);

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
    private PostId contributorsPending;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
        administrator = member("administrator");
        editor = member("editor");
        author = member("author");
        contributor = member("contributor");
        authorsPublished = TestData.postBy(dataSource, site, author, "post", "publish", "A");
        contributorsPending = TestData.postBy(dataSource, site, contributor, "post", "pending", "C");
    }

    private UserId member(String role) {
        UserId user = TestData.profile(dataSource, role);
        TestData.member(dataSource, TestData.MAIN, user, "[\"" + role + "\"]");
        return user;
    }

    private AutosaveChanges changes() {
        return AutosaveChanges.builder().title("x").build();
    }

    @Test
    void whoMayEditThePostMayAutosaveIt() throws Exception {
        for (UserId user : new UserId[] {administrator, editor, author}) {
            assertThat(Identities.onSite(
                            site, Identities.user(user), () -> revisions.autosave(authorsPublished, changes())))
                    .isInstanceOf(AutosaveResult.Stored.class);
        }
        assertThat(Identities.onSite(site, Identities.system(), () -> revisions.autosave(authorsPublished, changes())))
                .isInstanceOf(AutosaveResult.Stored.class);
        assertThatThrownBy(() -> Identities.onSite(
                        site, Identities.user(contributor), () -> revisions.autosave(authorsPublished, changes())))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> Identities.onSite(
                        site, Identities.anonymous(), () -> revisions.autosave(authorsPublished, changes())))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(Identities.onSite(
                        site, Identities.user(contributor), () -> revisions.autosave(contributorsPending, changes())))
                .isInstanceOf(AutosaveResult.Stored.class);
        assertThatThrownBy(() -> Identities.onSite(
                        site, Identities.user(author), () -> revisions.autosave(contributorsPending, changes())))
                .isInstanceOf(ForbiddenException.class);
        assertThat(Jdbc.count(
                        dataSource, "select count(*) from qp_revision where post_id = ?", authorsPublished.value()))
                .isEqualTo(4);
    }

    @Test
    void refusalsBeforeTheWriteLeaveTheTransactionUsable() throws Exception {
        PostId attachment = TestData.postBy(dataSource, site, editor, "attachment", "inherit", "Img");
        Identities.onSite(site, Identities.user(contributor), () -> {
            assertThatThrownBy(() -> revisions.autosave(authorsPublished, changes()))
                    .isInstanceOf(ForbiddenException.class);
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            assertThatThrownBy(() -> revisions.autosave(attachment, changes()))
                    .isInstanceOfSatisfying(
                            RevisionException.class,
                            refused -> assertThat(refused.code()).isEqualTo("autosave_not_supported"));
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            assertThatThrownBy(() -> revisions.autosave(
                            contributorsPending,
                            AutosaveChanges.builder()
                                    .meta("color", TextNode.valueOf("red"))
                                    .build()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .isNotInstanceOf(RevisionException.class)
                    .hasMessageContaining("color");
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            assertThat(revisions.autosave(contributorsPending, changes())).isInstanceOf(AutosaveResult.Stored.class);
            return null;
        });
    }
}
