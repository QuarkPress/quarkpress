package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteCreated;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.TermId;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.persistence.NetworkScopePolicy;
import org.quarkpress.taxonomy.Taxonomy;
import org.quarkpress.taxonomy.TermCreated;

class DefaultTermsDbTest {

    @Taxonomy(key = "genre", objectTypes = "post", hierarchical = true, defaultTerm = "General")
    static class Genre {}

    /** Announces a site as the multisite module does: inside a network transaction. */
    @ApplicationScoped
    public static class SiteCreator {
        @Inject
        NetworkScope network;

        @Inject
        Event<SiteCreated> created;

        void announce(SiteId site) {
            network.run(() -> created.fire(new SiteCreated(
                    site,
                    Instant.parse("2026-10-21T10:00:00Z"),
                    new SiteInfo(site, "terms.test", "/", "terms", 1, false, false, false, false))));
        }
    }

    @ApplicationScoped
    public static class AllowCreator implements NetworkScopePolicy {
        @Override
        public boolean allows(Class<?> caller) {
            return caller == SiteCreator.class;
        }
    }

    @ApplicationScoped
    static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();

        List<String> seen() {
            return seen;
        }

        void created(@Observes TermCreated event) {
            seen.add(event.term().taxonomy() + ":" + event.term().slug() + "@"
                    + event.site().value());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.multisite(
            DefaultTermsDbTest.class, Genre.class, SiteCreator.class, AllowCreator.class, Recorder.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    SiteCreator creator;

    @Inject
    Recorder recorder;

    private static long termId(AgroalDataSource dataSource, SiteId site, String taxonomy, String slug) {
        return Jdbc.count(
                dataSource,
                "select id from qp_term where site_id = ? and taxonomy = ? and slug = ?",
                site.value(),
                taxonomy,
                slug);
    }

    @Test
    void aCreatedSiteGetsUncategorizedAndTheCustomDefaults() {
        SiteId site = TestData.bareSite(dataSource);
        recorder.seen().clear();
        creator.announce(site);
        long uncategorized = termId(dataSource, site, "category", "uncategorized");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(name, '|', count, '|', description) from qp_term where id = ?",
                        uncategorized))
                .containsExactly("Uncategorized|0|");
        assertThat(TestData.option(dataSource, site, "default_category")).containsExactly(Long.toString(uncategorized));
        long general = termId(dataSource, site, "genre", "general");
        assertThat(TestData.option(dataSource, site, "default_term_genre")).containsExactly(Long.toString(general));
        assertThat(recorder.seen()).containsExactly("genre:general@" + site.value());
    }

    @Test
    void anExistingTermOfThatNameIsReused() {
        SiteId site = TestData.bareSite(dataSource);
        TermId existing = TestData.term(dataSource, site, "genre", "general", "gen", null);
        creator.announce(site);
        assertThat(TestData.option(dataSource, site, "default_term_genre"))
                .containsExactly(Long.toString(existing.value()));
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where site_id = ? and taxonomy = 'genre'",
                        site.value()))
                .isEqualTo(1);
    }

    @Test
    void theMainSiteGotItsCustomDefaultAtStartup() {
        long general = termId(dataSource, TestData.MAIN, "genre", "general");
        assertThat(general).isPositive();
        assertThat(TestData.option(dataSource, TestData.MAIN, "default_term_genre"))
                .containsExactly(Long.toString(general));
    }
}
