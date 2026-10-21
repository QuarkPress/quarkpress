package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.TermDraft;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermRef;
import org.quarkpress.taxonomy.TermWriteContext;
import org.quarkpress.taxonomy.TermWriteFilter;
import org.quarkpress.taxonomy.Terms;

/**
 * Concurrent writers of one site get one term per name, without unique-key failures or deadlocks. The writers meet in
 * a write filter, which runs after the duplicate checks and before the insert: a writer waits there (up to two seconds)
 * for the other, so without the site's post lock both would pass their checks before either inserts. With the lock the
 * second writer is still waiting for the lock, the first one goes on alone, and the second sees its term.
 */
class AssignConcurrencyDbTest {

    @ApplicationScoped
    static class Rendezvous implements TermWriteFilter {
        private volatile CountDownLatch arrivals = new CountDownLatch(0);

        void expect(int writers) {
            arrivals = new CountDownLatch(writers);
        }

        @Override
        public TermDraft apply(TermDraft draft, TermWriteContext context) {
            CountDownLatch latch = arrivals;
            latch.countDown();
            try {
                latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return draft;
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.multisite(AssignConcurrencyDbTest.class, Rendezvous.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Terms terms;

    @Inject
    Rendezvous rendezvous;

    @BeforeEach
    void bothWritersMeet() {
        rendezvous.expect(2);
    }

    @Test
    void twoEditorsTaggingWithOneNewTagShareIt() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId one = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, site, one, "[\"editor\"]");
        UserId two = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, site, two, "[\"editor\"]");
        PostId first = TestData.post(dataSource, site, "post", "publish", "first", "First");
        PostId second = TestData.post(dataSource, site, "post", "publish", "second", "Second");
        List<Object> results = Races.run(List.<Callable<Object>>of(
                () -> Identities.onSite(
                        site,
                        Identities.user(one),
                        () -> terms.setPostTerms(first, "post_tag", List.of(TermRef.name("Launch")), false)),
                () -> Identities.onSite(
                        site,
                        Identities.user(two),
                        () -> terms.setPostTerms(second, "post_tag", List.of(TermRef.name("Launch")), false))));
        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(List.class));
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(slug, '|', count) from qp_term where site_id = ?" + " and taxonomy = 'post_tag'",
                        site.value()))
                .containsExactly("launch|2");
    }

    @Test
    void twoCreatesOfOneTagGiveOneTermAndOneRefusal() throws Exception {
        SiteId site = TestData.site(dataSource);
        List<Object> results = Races.run(List.<Callable<Object>>of(
                () -> Identities.onSite(
                        site,
                        Identities.system(),
                        () -> terms.create(
                                "post_tag", TermChanges.builder().name("Launch").build())),
                () -> Identities.onSite(
                        site,
                        Identities.system(),
                        () -> terms.create(
                                "post_tag", TermChanges.builder().name("Launch").build()))));
        assertThat(results.stream()
                        .filter(TermException.class::isInstance)
                        .map(TermException.class::cast)
                        .map(TermException::code))
                .containsExactly("term_exists");
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where site_id = ? and taxonomy = 'post_tag'",
                        site.value()))
                .isEqualTo(1);
    }
}
