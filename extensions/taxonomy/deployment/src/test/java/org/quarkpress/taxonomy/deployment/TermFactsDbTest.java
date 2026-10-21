package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.CapObject;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.MissingObject;
import org.quarkpress.taxonomy.Taxonomy;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.runtime.TermCapabilityFacts;
import org.quarkpress.taxonomy.runtime.TermEntity;
import org.quarkpress.taxonomy.runtime.TermRepository;

/** WordPress's term capabilities, answered with the facts of stored terms. */
class TermFactsDbTest {

    @Taxonomy(key = "genre", objectTypes = "post", hierarchical = true)
    static class Genre {}

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.multisite(TermFactsDbTest.class, Genre.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Capabilities capabilities;

    @Inject
    TermCapabilityFacts facts;

    @Inject
    TermRepository terms;

    private Optional<CapObject> term(SiteId site, TermId id) throws Exception {
        TermSnapshot stored = Identities.onSite(
                site,
                Identities.system(),
                () -> terms.findTerm(site, id).map(TermEntity::toSnapshot).orElseThrow());
        return Optional.of(facts.of(stored));
    }

    @Test
    void authorsAssignTermsButEditorsManageThem() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, site, editor, "[\"editor\"]");
        UserId author = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, author, "[\"author\"]");
        UserId contributor = TestData.profile(dataSource, "contributor");
        TestData.member(dataSource, site, contributor, "[\"contributor\"]");
        Optional<CapObject> tag = term(site, TestData.term(dataSource, site, "post_tag", "News", "news", null));
        Optional<CapObject> category = term(site, TestData.term(dataSource, site, "category", "World", "world", null));
        assertThat(capabilities.userCan(author, site, "edit_term", tag)).isFalse();
        assertThat(capabilities.userCan(author, site, "assign_term", tag)).isTrue();
        assertThat(capabilities.userCan(author, site, "assign_term", category)).isTrue();
        assertThat(capabilities.userCan(contributor, site, "assign_term", tag)).isTrue();
        assertThat(capabilities.userCan(contributor, site, "delete_term", tag)).isFalse();
        assertThat(capabilities.userCan(editor, site, "edit_term", category)).isTrue();
        assertThat(capabilities.userCan(editor, site, "delete_term", tag)).isTrue();
    }

    @Test
    void theDefaultCategoryCannotBeDeletedByAnyone() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId admin = TestData.profile(dataSource, "administrator");
        TestData.member(dataSource, site, admin, "[\"administrator\"]");
        Optional<CapObject> uncategorized = term(site, TestData.defaultCategory(dataSource, site));
        assertThat(capabilities.userCan(admin, site, "edit_term", uncategorized))
                .isTrue();
        assertThat(capabilities.userCan(admin, site, "delete_term", uncategorized))
                .isFalse();
        TermId generalId = TestData.term(dataSource, site, "genre", "General", "general", null);
        Optional<CapObject> general = term(site, generalId);
        assertThat(capabilities.userCan(admin, site, "delete_term", general)).isTrue();
        TestData.siteOption(dataSource, site, "default_term_genre", Long.toString(generalId.value()));
        assertThat(capabilities.userCan(admin, site, "delete_term", general)).isFalse();
    }

    @Test
    void missingTermsAndUnregisteredTaxonomiesAreRefused() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId admin = TestData.profile(dataSource, "administrator");
        TestData.member(dataSource, site, admin, "[\"administrator\"]");
        Optional<CapObject> menu = term(site, TestData.term(dataSource, site, "nav_menu", "Main", "main", null));
        assertThat(capabilities.userCan(admin, site, "edit_term", menu)).isFalse();
        assertThat(capabilities.userCan(admin, site, "edit_term", Optional.of(new MissingObject(999_999))))
                .isFalse();
        assertThat(capabilities.userCan(admin, site, "edit_term", Optional.empty()))
                .isFalse();
    }
}
