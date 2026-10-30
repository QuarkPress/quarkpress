package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.taxonomy.PostTermsAccess;
import org.quarkpress.taxonomy.Taxonomy;
import org.quarkpress.taxonomy.TermPermissions;

/** The term permission facade answers WordPress's term checks and the REST read rule of a post's terms. */
class TermPermissionsDbTest {

    @Taxonomy(key = "genre", objectTypes = "page", hierarchical = true)
    static class Genre {}

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.multisite(TermPermissionsDbTest.class, Genre.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    TermPermissions permissions;

    SiteId site;
    SecurityIdentity administrator;
    SecurityIdentity editor;
    SecurityIdentity carol;
    SecurityIdentity dave;
    SecurityIdentity erin;
    SecurityIdentity anonymous;
    TermId defaultCategory;
    TermId otherCategory;
    PostId carolsPublished;
    PostId carolsDraft;
    PostId aPage;

    @BeforeEach
    void site() {
        site = TestData.site(dataSource);
        administrator = member("administrator");
        editor = member("editor");
        UserId carolId = memberId("author");
        carol = Identities.user(carolId);
        dave = member("contributor");
        erin = member("subscriber");
        anonymous = Identities.anonymous();
        defaultCategory = TestData.defaultCategory(dataSource, site);
        otherCategory = TestData.term(dataSource, site, "category", "World", "world", null);
        carolsPublished = postBy(carolId, "post", "publish");
        carolsDraft = postBy(carolId, "post", "draft");
        aPage = postBy(carolId, "page", "publish");
    }

    private UserId memberId(String role) {
        UserId user = TestData.profile(dataSource, role);
        TestData.member(dataSource, site, user, "[\"" + role + "\"]");
        return user;
    }

    private SecurityIdentity member(String role) {
        return Identities.user(memberId(role));
    }

    private PostId postBy(UserId author, String type, String status) {
        PostId post = TestData.post(dataSource, site, type, status, "", type + " " + status);
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", author.value(), post.value());
        return post;
    }

    private <T> T as(SecurityIdentity identity, Callable<T> work) throws Exception {
        return Identities.onSite(site, identity, work);
    }

    @Test
    void createDependsOnTheHierarchy() throws Exception {
        assertThat(as(carol, () -> permissions.canCreate("post_tag"))).isTrue();
        assertThat(as(dave, () -> permissions.canCreate("post_tag"))).isTrue();
        assertThat(as(erin, () -> permissions.canCreate("post_tag"))).isFalse();
        assertThat(as(carol, () -> permissions.canCreate("category"))).isFalse();
        assertThat(as(editor, () -> permissions.canCreate("category"))).isTrue();
        assertThat(as(administrator, () -> permissions.canCreate("no_such_taxonomy")))
                .isFalse();
    }

    @Test
    void theDefaultCategoryIsNeverDeletable() throws Exception {
        assertThat(as(administrator, () -> permissions.canDelete(defaultCategory)))
                .isFalse();
        assertThat(as(administrator, () -> permissions.canEdit(defaultCategory)))
                .isTrue();
        assertThat(as(administrator, () -> permissions.canDelete(otherCategory)))
                .isTrue();
        assertThat(as(carol, () -> permissions.canDelete(otherCategory))).isFalse();
        assertThat(as(carol, () -> permissions.canEdit(otherCategory))).isFalse();
        assertThat(as(editor, () -> permissions.canEdit(TermId.of(987_654)))).isFalse();
        assertThat(as(administrator, () -> permissions.canDelete(TermId.of(987_654))))
                .isFalse();
    }

    @Test
    void taxonomyLevelChecks() throws Exception {
        assertThat(as(carol, () -> permissions.canEditTerms("category"))).isFalse();
        assertThat(as(editor, () -> permissions.canEditTerms("category"))).isTrue();
        assertThat(as(dave, () -> permissions.canAssign("category"))).isTrue();
        assertThat(as(erin, () -> permissions.canAssign("category"))).isFalse();
        assertThat(as(administrator, () -> permissions.canAssign("no_such_taxonomy")))
                .isFalse();
    }

    @Test
    void postTermsAccess() throws Exception {
        assertThat(as(anonymous, () -> permissions.postTermsAccess(PostId.of(987_654), "category")))
                .isEqualTo(PostTermsAccess.MISSING_POST);
        assertThat(as(anonymous, () -> permissions.postTermsAccess(carolsPublished, "category")))
                .isEqualTo(PostTermsAccess.ALLOWED);
        assertThat(as(anonymous, () -> permissions.postTermsAccess(carolsDraft, "category")))
                .isEqualTo(PostTermsAccess.DENIED);
        assertThat(as(carol, () -> permissions.postTermsAccess(carolsDraft, "category")))
                .isEqualTo(PostTermsAccess.ALLOWED);
        assertThat(as(dave, () -> permissions.postTermsAccess(carolsDraft, "category")))
                .isEqualTo(PostTermsAccess.DENIED);
        assertThat(as(editor, () -> permissions.postTermsAccess(aPage, "category")))
                .isEqualTo(PostTermsAccess.DENIED);
        assertThat(as(anonymous, () -> permissions.postTermsAccess(aPage, "genre")))
                .isEqualTo(PostTermsAccess.ALLOWED);
    }

    @Test
    void anotherSitesTermsAndPostsAreMissing() throws Exception {
        SiteId other = TestData.site(dataSource);
        TermId foreignTerm = TestData.term(dataSource, other, "category", "Elsewhere", "elsewhere", null);
        PostId foreignPost = TestData.post(dataSource, other, "post", "publish", "", "elsewhere");
        assertThat(as(administrator, () -> permissions.canEdit(foreignTerm))).isFalse();
        assertThat(as(administrator, () -> permissions.canDelete(foreignTerm))).isFalse();
        assertThat(as(administrator, () -> permissions.postTermsAccess(foreignPost, "category")))
                .isEqualTo(PostTermsAccess.MISSING_POST);
        // the same rows asked for their own site are found
        assertThat(Identities.onSite(other, Identities.system(), () -> permissions.canEdit(foreignTerm)))
                .isTrue();
        assertThat(Identities.onSite(
                        other, Identities.anonymous(), () -> permissions.postTermsAccess(foreignPost, "category")))
                .isEqualTo(PostTermsAccess.ALLOWED);
    }

    @Test
    void anExplicitIdentityIsUsedInsteadOfTheCurrentOne() throws Exception {
        assertThat(as(erin, () -> permissions.canCreate(editor, "category"))).isTrue();
        assertThat(as(editor, () -> permissions.canDelete(carol, otherCategory)))
                .isFalse();
        assertThat(as(anonymous, () -> permissions.postTermsAccess(carol, carolsDraft, "category")))
                .isEqualTo(PostTermsAccess.ALLOWED);
    }
}
