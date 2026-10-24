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
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostException;
import org.quarkpress.revisions.Revisions;

/** Saving a revision on demand needs the right to edit the post. */
class SaveCapabilityDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(SaveCapabilityDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Revisions revisions;

    @Inject
    TransactionManager transactions;

    private UserId member(SiteId site, String role) {
        UserId user = TestData.profile(dataSource, role);
        TestData.member(dataSource, TestData.MAIN, user, "[\"" + role + "\"]");
        return user;
    }

    @Test
    void whoMayEditThePostMaySaveARevisionOfIt() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId author = member(site, "author");
        UserId contributor = member(site, "contributor");
        UserId editor = member(site, "editor");
        PostId published = TestData.postBy(dataSource, site, author, "post", "publish", "Mine");
        assertThat(Identities.onSite(site, Identities.user(author), () -> revisions.save(published)))
                .isPresent();
        assertThatThrownBy(() -> Identities.onSite(site, Identities.user(contributor), () -> revisions.save(published)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> Identities.onSite(site, Identities.anonymous(), () -> revisions.save(published)))
                .isInstanceOf(UnauthorizedException.class);
        Jdbc.update(dataSource, "update qp_post set title = 'Edited' where id = ?", published.value());
        assertThat(Identities.onSite(site, Identities.user(editor), () -> revisions.save(published)))
                .isPresent();
        assertThat(Identities.onSite(site, Identities.system(), () -> revisions.save(published)))
                .isEmpty();
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_revision where post_id = ?", published.value()))
                .isEqualTo(2);
        assertThatThrownBy(() ->
                        Identities.onSite(site, Identities.user(editor), () -> revisions.save(PostId.of(999_999_999L))))
                .isInstanceOfSatisfying(
                        PostException.class,
                        refused -> assertThat(refused.code()).isEqualTo("invalid_post"));
        SiteId other = TestData.site(dataSource);
        assertThatThrownBy(() -> Identities.onSite(other, Identities.user(editor), () -> revisions.save(published)))
                .isInstanceOfSatisfying(
                        PostException.class,
                        refused -> assertThat(refused.code()).isEqualTo("invalid_post"));
    }

    @Test
    void aRefusalLeavesTheTransactionUsable() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId contributor = member(site, "contributor");
        UserId editor = member(site, "editor");
        PostId published = TestData.postBy(dataSource, site, editor, "post", "publish", "Theirs");
        Identities.onSite(site, Identities.user(contributor), () -> {
            assertThatThrownBy(() -> revisions.save(published)).isInstanceOf(ForbiddenException.class);
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            assertThatThrownBy(() -> revisions.save(PostId.of(999_999_999L))).isInstanceOf(PostException.class);
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            return null;
        });
    }
}
