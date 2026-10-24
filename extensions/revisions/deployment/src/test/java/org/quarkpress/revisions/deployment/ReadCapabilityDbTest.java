package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.revisions.RevisionCount;
import org.quarkpress.revisions.RevisionQuery;
import org.quarkpress.revisions.Revisions;

/** Every read of a post's revisions needs the right to edit the post, as WordPress's REST layer decides. */
class ReadCapabilityDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(ReadCapabilityDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Revisions revisions;

    @Inject
    TransactionManager transactions;

    private SiteId site;
    private UserId author;
    private UserId contributor;
    private UserId editor;
    private PostId authorsPost;
    private PostId contributorsDraft;
    private RevisionId ofAuthorsPost;
    private RevisionId ofContributorsDraft;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
        author = member("author");
        contributor = member("contributor");
        editor = member("editor");
        authorsPost = TestData.postBy(dataSource, site, author, "post", "publish", "A");
        contributorsDraft = TestData.postBy(dataSource, site, contributor, "post", "draft", "C");
        ofAuthorsPost = TestData.revision(
                dataSource, site, authorsPost, author, "a", "", "", "{}", false, "2026-01-15T12:00:00Z");
        // the author's post holds an autosave too, so every read finds something on both posts
        TestData.revision(dataSource, site, authorsPost, author, "auto", "", "", "{}", true, "2026-01-15T12:01:00Z");
        ofContributorsDraft = TestData.revision(
                dataSource, site, contributorsDraft, contributor, "c", "", "", "{}", true, "2026-01-15T12:00:00Z");
    }

    private UserId member(String role) {
        UserId user = TestData.profile(dataSource, role);
        TestData.member(dataSource, TestData.MAIN, user, "[\"" + role + "\"]");
        return user;
    }

    private List<Callable<Object>> reads(PostId post, RevisionId revision) {
        return List.of(
                () -> revisions.find(revision),
                () -> revisions.ofPost(post, RevisionQuery.all()),
                () -> revisions.autosaveOf(post, Optional.empty()),
                () -> revisions.autosavesOf(post),
                () -> revisions.latestAndCount(post));
    }

    private void allowed(SecurityIdentity identity, PostId post, RevisionId revision) throws Exception {
        for (Callable<Object> read : reads(post, revision)) {
            Object result = Identities.onSite(site, identity, read);
            if (result instanceof Optional<?> found) {
                assertThat(found).isPresent();
            } else if (result instanceof List<?> listed) {
                assertThat(listed).isNotEmpty();
            } else if (result instanceof RevisionCount counted) {
                assertThat(counted.latest()).isPresent();
                assertThat(counted.count()).isPositive();
            } else {
                throw new AssertionError("unexpected result " + result);
            }
        }
    }

    private void refused(SecurityIdentity identity, PostId post, RevisionId revision, Class<?> exception) {
        for (Callable<Object> read : reads(post, revision)) {
            assertThatThrownBy(() -> Identities.onSite(site, identity, read)).isInstanceOf(exception);
        }
    }

    @Test
    void whoMayEditThePostMayReadItsRevisions() throws Exception {
        allowed(Identities.user(author), authorsPost, ofAuthorsPost);
        allowed(Identities.user(editor), authorsPost, ofAuthorsPost);
        allowed(Identities.system(), authorsPost, ofAuthorsPost);
        refused(Identities.user(contributor), authorsPost, ofAuthorsPost, ForbiddenException.class);
        refused(Identities.anonymous(), authorsPost, ofAuthorsPost, UnauthorizedException.class);
        allowed(Identities.user(contributor), contributorsDraft, ofContributorsDraft);
        allowed(Identities.user(editor), contributorsDraft, ofContributorsDraft);
        refused(Identities.user(author), contributorsDraft, ofContributorsDraft, ForbiddenException.class);
    }

    @Test
    void aRefusedReadLeavesTheTransactionUsable() throws Exception {
        Identities.onSite(site, Identities.user(contributor), () -> {
            assertThatThrownBy(() -> revisions.find(ofAuthorsPost)).isInstanceOf(ForbiddenException.class);
            assertThat(transactions.getStatus()).isEqualTo(Status.STATUS_ACTIVE);
            assertThat(revisions.find(ofContributorsDraft)).isPresent();
            return null;
        });
    }
}
