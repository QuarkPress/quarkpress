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
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.Terms;

/** Updating a term needs edit_term on it, as WordPress's REST layer checks. */
class UpdateCapabilityDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.multisite(UpdateCapabilityDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Terms terms;

    @Test
    void authorsCannotEditTagsEditorsCan() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId author = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, author, "[\"author\"]");
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, site, editor, "[\"editor\"]");
        TermId tag = TestData.term(dataSource, site, "post_tag", "News", "news", null);
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.user(author),
                        () -> terms.update(
                                tag,
                                "post_tag",
                                TermChanges.builder().name("Mine").build())))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.anonymous(),
                        () -> terms.update(
                                tag,
                                "post_tag",
                                TermChanges.builder().name("Anon").build())))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(Jdbc.strings(dataSource, "select name from qp_term where id = ?", tag.value()))
                .containsExactly("News");
        assertThat(Identities.onSite(
                                site,
                                Identities.user(editor),
                                () -> terms.update(
                                        tag,
                                        "post_tag",
                                        TermChanges.builder().name("Edited").build()))
                        .name())
                .isEqualTo("Edited");
    }

    @Test
    void theDefaultCategoryCanBeRenamed() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId admin = TestData.profile(dataSource, "administrator");
        TestData.member(dataSource, site, admin, "[\"administrator\"]");
        TermId uncategorized = TestData.defaultCategory(dataSource, site);
        assertThat(Identities.onSite(
                                site,
                                Identities.user(admin),
                                () -> terms.update(
                                        uncategorized,
                                        "category",
                                        TermChanges.builder().name("General").build()))
                        .slug())
                .isEqualTo("uncategorized");
    }

    @Test
    void aRefusedCapabilityLeavesTheCallersTransactionUsable() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId author = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, author, "[\"author\"]");
        TermId tag = TestData.term(dataSource, site, "post_tag", "Locked", "locked", null);
        String kept = Identities.onSite(site, Identities.user(author), () -> {
            assertThatThrownBy(() -> terms.update(
                            tag, "post_tag", TermChanges.builder().name("Mine").build()))
                    .isInstanceOf(ForbiddenException.class);
            return terms.create(
                            "post_tag", TermChanges.builder().name("Allowed").build())
                    .slug();
        });
        assertThat(kept).isEqualTo("allowed");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select name from qp_term where site_id = ? and slug in ('locked', 'allowed') order by slug",
                        site.value()))
                .containsExactly("Allowed", "Locked");
    }
}
