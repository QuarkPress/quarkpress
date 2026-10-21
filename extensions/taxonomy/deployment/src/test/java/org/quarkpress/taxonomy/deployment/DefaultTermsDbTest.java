package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteCreated;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.TermId;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.persistence.NetworkScopePolicy;
import org.quarkpress.posts.runtime.PostLocks;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.Taxonomy;
import org.quarkpress.taxonomy.TermCreated;
import org.quarkpress.taxonomy.runtime.DefaultTerms;

class DefaultTermsDbTest {

    @Taxonomy(key = "genre", objectTypes = "post", hierarchical = true, defaultTerm = "General")
    static class Genre {}

    /** A default term the save filters refuse (a blank name): logged, and startup goes on. */
    @Taxonomy(key = "blank", objectTypes = "post", defaultTerm = "   ")
    static class Blank {}

    /** Announces a site as the multisite module does: inside a network transaction. */
    @ApplicationScoped
    public static class SiteCreator {
        @Inject
        NetworkScope network;

        @Inject
        Event<SiteCreated> created;

        void announce(SiteId site) {
            network.run(() -> created.fire(event(site)));
        }

        /** A site creation that fails after the announcement, so its transaction rolls back. */
        void announceAndFail(SiteId site) {
            network.run(() -> {
                created.fire(event(site));
                throw new IllegalStateException("the site creation failed");
            });
        }

        private static SiteCreated event(SiteId site) {
            return new SiteCreated(
                    site,
                    Instant.parse("2026-10-21T10:00:00Z"),
                    new SiteInfo(site, "terms.test", "/", "terms", 1, false, false, false, false));
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
                    DefaultTermsDbTest.class,
                    Genre.class,
                    Blank.class,
                    SiteCreator.class,
                    AllowCreator.class,
                    Recorder.class)
            .setLogRecordPredicate(record -> record.getLoggerName().equals(DefaultTerms.class.getName()))
            .assertLogRecords(records -> assertThat(records)
                    .as("a warning for each refused default term (at startup and at each site creation)")
                    .anySatisfy(record ->
                            assertThat(Arrays.asList(record.getParameters())).contains("blank", "empty_term_name")));

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    SiteCreator creator;

    @Inject
    Recorder recorder;

    @Inject
    DefaultTerms defaults;

    @Inject
    Taxonomies taxonomies;

    @Inject
    PostLocks locks;

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
        // the refused default term leaves its option unset and the creation goes on
        assertThat(TestData.option(dataSource, site, "default_term_blank")).isEmpty();
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where site_id = ? and taxonomy = 'blank'",
                        site.value()))
                .isZero();
    }

    @Test
    void aSiteCreationThatFailsKeepsNoDefaultTerm() {
        SiteId site = TestData.bareSite(dataSource);
        assertThatThrownBy(() -> creator.announceAndFail(site))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the site creation failed");
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_term where site_id = ?", site.value()))
                .isZero();
        assertThat(TestData.option(dataSource, site, "default_category")).containsExactly("0");
        assertThat(TestData.option(dataSource, site, "default_term_genre")).isEmpty();
    }

    @Test
    void ensuringTwiceFindsTheSameTermAndARefusalLeavesTheOptionUnset() throws Exception {
        SiteId site = TestData.site(dataSource);
        List<Optional<TermId>> ensured = Identities.onSite(site, Identities.system(), () -> {
            locks.lock(site);
            Optional<TermId> first = defaults.ensure(site, taxonomies.require("genre"));
            Optional<TermId> second = defaults.ensure(site, taxonomies.require("genre"));
            Optional<TermId> refused = defaults.ensure(site, taxonomies.require("blank"));
            return List.of(first, second, refused);
        });
        assertThat(ensured.get(0)).isPresent().isEqualTo(ensured.get(1));
        assertThat(ensured.get(2)).isEmpty();
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where site_id = ? and taxonomy = 'genre'",
                        site.value()))
                .isEqualTo(1);
        assertThat(TestData.option(dataSource, site, "default_term_genre"))
                .containsExactly(Long.toString(ensured.get(0).orElseThrow().value()));
        assertThat(TestData.option(dataSource, site, "default_term_blank")).isEmpty();
    }

    @Test
    void theStartupCheckGivesEveryExistingSiteItsCustomDefaults() {
        SiteId site = TestData.site(dataSource);
        assertThat(TestData.option(dataSource, site, "default_term_genre")).isEmpty();
        defaults.ensureEverySite();
        long general = termId(dataSource, site, "genre", "general");
        assertThat(general).isPositive();
        assertThat(TestData.option(dataSource, site, "default_term_genre")).containsExactly(Long.toString(general));
        assertThat(TestData.option(dataSource, site, "default_term_blank")).isEmpty();
    }

    @Test
    void theCurrentDefaultTermIsReadOnlyForTheBoundSite() throws Exception {
        SiteId site = TestData.site(dataSource);
        SiteId other = TestData.site(dataSource);
        assertThatThrownBy(() -> Identities.onSite(
                        site, Identities.system(), () -> defaults.current(other, taxonomies.require("category"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the default term of site " + other.value() + " is read bound to site " + site.value());
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> defaults.current(site, taxonomies.require("category"))))
                .contains(TestData.defaultCategory(dataSource, site));
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
