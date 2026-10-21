package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.Terms;

/** Creating terms is checked as WordPress's REST layer checks it. */
class CreateCapabilityDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.multisite(CreateCapabilityDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Terms terms;

    private UserId member(SiteId site, String role) {
        UserId user = TestData.profile(dataSource, role);
        TestData.member(dataSource, site, user, "[\"" + role + "\"]");
        return user;
    }

    @Test
    void contributorsCreateTagsButNotCategories() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId contributor = member(site, "contributor");
        assertThat(Identities.onSite(
                                site,
                                Identities.user(contributor),
                                () -> terms.create(
                                        "post_tag",
                                        TermChanges.builder().name("Mine").build()))
                        .slug())
                .isEqualTo("mine");
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.user(contributor),
                        () -> terms.create(
                                "category", TermChanges.builder().name("Mine").build())))
                .isInstanceOf(ForbiddenException.class);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where site_id = ? and taxonomy = 'category'"
                                + " and slug = 'mine'",
                        site.value()))
                .isZero();
    }

    @Test
    void editorsCreateCategoriesAndTheSystemCreatesAnything() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = member(site, "editor");
        assertThat(Identities.onSite(
                                site,
                                Identities.user(editor),
                                () -> terms.create(
                                        "category",
                                        TermChanges.builder().name("Ed").build()))
                        .taxonomy())
                .isEqualTo("category");
        assertThat(Identities.onSite(
                                site,
                                Identities.system(),
                                () -> terms.create(
                                        "category",
                                        TermChanges.builder().name("Sys").build()))
                        .slug())
                .isEqualTo("sys");
    }

    @Test
    void nobodyAuthenticatedIsUnauthorizedAndAMemberOfAnotherSiteForbidden() throws Exception {
        SiteId site = TestData.site(dataSource);
        SiteId other = TestData.site(dataSource);
        UserId elsewhere = member(other, "administrator");
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.anonymous(),
                        () -> terms.create(
                                "post_tag", TermChanges.builder().name("Anon").build())))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.user(elsewhere),
                        () -> terms.create(
                                "post_tag",
                                TermChanges.builder().name("Visitor").build())))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aRefusedCapabilityLeavesTheCallersTransactionUsable() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId contributor = member(site, "contributor");
        String kept = Identities.onSite(site, Identities.user(contributor), () -> {
            assertThatThrownBy(() -> terms.create(
                            "category", TermChanges.builder().name("Denied").build()))
                    .isInstanceOf(ForbiddenException.class);
            return terms.create(
                            "post_tag", TermChanges.builder().name("Allowed").build())
                    .slug();
        });
        assertThat(kept).isEqualTo("allowed");
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where site_id = ? and slug = 'allowed'",
                        site.value()))
                .isEqualTo(1);
        assertThat(Jdbc.count(
                        dataSource, "select count(*) from qp_term where site_id = ? and slug = 'denied'", site.value()))
                .isZero();
    }
}
