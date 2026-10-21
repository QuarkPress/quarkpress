package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.core.deployment.spi.TaxonomyBuildItem;
import org.quarkpress.taxonomy.Taxonomy;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermRef;
import org.quarkpress.taxonomy.Terms;

/**
 * Assignments and removals need edit_post on the post and assign_term on each term named; a name that creates a term
 * needs the creating capability.
 */
class AssignCapabilityDbTest {

    @Taxonomy(key = "secret", objectTypes = "post", publicly = false)
    static class Secret {}

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.multisite(AssignCapabilityDbTest.class, Secret.class)
            .addBuildChainCustomizer(chain -> chain.addBuildStep(new BuildStep() {
                        @Override
                        public void execute(BuildContext context) {
                            context.produce(new TaxonomyBuildItem(TaxonomyDefinition.builder("guarded", List.of("post"))
                                    .capabilities(Map.of("assign_terms", "manage_categories"))
                                    .build()));
                        }
                    })
                    .produces(TaxonomyBuildItem.class)
                    .build());

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Terms terms;

    private PostId postBy(SiteId site, UserId author, String status) {
        PostId post = TestData.post(dataSource, site, "post", status, "", "By " + author.value());
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", author.value(), post.value());
        return post;
    }

    @Test
    void contributorsTagTheirOwnDraftsButCreateNoCategories() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId contributor = TestData.profile(dataSource, "contributor");
        TestData.member(dataSource, site, contributor, "[\"contributor\"]");
        PostId own = postBy(site, contributor, "draft");
        assertThat(Identities.onSite(
                        site,
                        Identities.user(contributor),
                        () -> terms.setPostTerms(own, "post_tag", List.of(TermRef.name("Mine")), false)))
                .hasSize(1);
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.user(contributor),
                        () -> terms.setPostTerms(own, "category", List.of(TermRef.name("New Category")), false)))
                .isInstanceOf(ForbiddenException.class);
        UserId other = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, other, "[\"author\"]");
        PostId theirs = postBy(site, other, "draft");
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.user(contributor),
                        () -> terms.setPostTerms(theirs, "post_tag", List.of(TermRef.name("Mine")), false)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.anonymous(),
                        () -> terms.setPostTerms(own, "post_tag", List.of(TermRef.name("Mine")), false)))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void assigningAndRemovingNeedAssignTermOnEveryTermNamed() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId author = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, author, "[\"author\"]");
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, site, editor, "[\"editor\"]");
        PostId own = postBy(site, author, "draft");
        TermId kept = TestData.term(dataSource, site, "guarded", "Kept", "kept", null);
        TermId other = TestData.term(dataSource, site, "guarded", "Other", "other", null);
        Identities.onSite(
                site, Identities.system(), () -> terms.setPostTerms(own, "guarded", List.of(TermRef.id(kept)), false));
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.user(author),
                        () -> terms.removePostTerms(own, "guarded", List.of(TermRef.id(kept)))))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.user(author),
                        () -> terms.removePostTerms(own, "guarded", List.of(TermRef.name("Kept")))))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.user(author),
                        () -> terms.setPostTerms(own, "guarded", List.of(TermRef.id(other)), true)))
                .isInstanceOf(ForbiddenException.class);
        assertThat(Jdbc.strings(dataSource, "select term_id from qp_term_assignment where post_id = ?", own.value()))
                .containsExactly(Long.toString(kept.value()));
        assertThat(Identities.onSite(
                        site,
                        Identities.user(editor),
                        () -> terms.removePostTerms(own, "guarded", List.of(TermRef.name("Kept")))))
                .isTrue();
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_term_assignment where post_id = ?", own.value()))
                .isZero();
    }

    @Test
    void refusedAssignmentsLeaveTheCallersTransactionUsable() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId author = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, author, "[\"author\"]");
        PostId own = postBy(site, author, "draft");
        TermId kept = TestData.term(dataSource, site, "guarded", "Kept", "kept", null);
        Identities.onSite(
                site, Identities.system(), () -> terms.setPostTerms(own, "guarded", List.of(TermRef.id(kept)), false));
        List<TermId> written = Identities.onSite(site, Identities.user(author), () -> {
            assertThatThrownBy(() -> terms.setPostTerms(own, "guarded", List.of(TermRef.id(kept)), true))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> terms.removePostTerms(own, "guarded", List.of(TermRef.id(kept))))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> terms.setPostTerms(own, "post_tag", List.of(TermRef.name("<b></b>")), true))
                    .isInstanceOfSatisfying(
                            TermException.class,
                            refused -> assertThat(refused.code()).isEqualTo("invalid_term_name"));
            return terms.setPostTerms(own, "post_tag", List.of(TermRef.name("Usable")), true);
        });
        assertThat(written).hasSize(1);
        assertThat(Jdbc.strings(
                        dataSource,
                        "select t.slug from qp_term_assignment a join qp_term t on t.id = a.term_id"
                                + " where a.post_id = ? order by t.slug",
                        own.value()))
                .containsExactly("kept", "usable");
    }

    @Test
    void readingThePostsTermsNeedsTheRightToReadThePostAndTheTaxonomy() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, site, editor, "[\"editor\"]");
        PostId draft = postBy(site, editor, "draft");
        PostId published = postBy(site, editor, "publish");
        assertThatThrownBy(
                        () -> Identities.onSite(site, Identities.anonymous(), () -> terms.postTerms(draft, "post_tag")))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(Identities.onSite(site, Identities.anonymous(), () -> terms.postTerms(published, "post_tag")))
                .isEmpty();
        assertThatThrownBy(() ->
                        Identities.onSite(site, Identities.anonymous(), () -> terms.postTerms(published, "secret")))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(Identities.onSite(site, Identities.user(editor), () -> terms.postTerms(published, "secret")))
                .isEmpty();
    }
}
