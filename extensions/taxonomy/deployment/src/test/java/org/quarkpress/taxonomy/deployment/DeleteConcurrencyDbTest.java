package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.taxonomy.TermDeleted;
import org.quarkpress.taxonomy.TermRef;
import org.quarkpress.taxonomy.Terms;

/** A category deleted while another editor publishes a post into it: no deadlock, exact counts either way. */
class DeleteConcurrencyDbTest {

    /**
     * When armed, holds a delete open after it has removed its term: it tells the test it got there, then waits (at
     * most three seconds) for the other writer to have saved its post, and half a second more for that writer to
     * reach the assignment.
     */
    @ApplicationScoped
    static class Pause {
        volatile CountDownLatch deleting;
        volatile CountDownLatch saved;

        void arm(CountDownLatch reached, CountDownLatch other) {
            deleting = reached;
            saved = other;
        }

        void disarm() {
            arm(null, null);
        }

        void deleted(@Observes TermDeleted event) throws InterruptedException {
            CountDownLatch reached = deleting;
            CountDownLatch other = saved;
            if (reached == null || other == null) {
                return;
            }
            reached.countDown();
            if (other.await(3, TimeUnit.SECONDS)) {
                Thread.sleep(500);
            }
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.multisite(DeleteConcurrencyDbTest.class, Pause.class);

    @Inject
    Pause pause;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Terms terms;

    @Inject
    Posts posts;

    @Test
    void deletingWhilePublishingLeavesExactCounts() throws Exception {
        for (int round = 0; round < 5; round++) {
            SiteId site = TestData.site(dataSource);
            UserId one = TestData.profile(dataSource, "editor");
            TestData.member(dataSource, site, one, "[\"editor\"]");
            UserId two = TestData.profile(dataSource, "editor");
            TestData.member(dataSource, site, two, "[\"editor\"]");
            TermId news = TestData.term(dataSource, site, "category", "News", "news", null);
            List<Object> results = Races.run(List.<Callable<Object>>of(
                    () -> Identities.onSite(site, Identities.user(one), () -> terms.delete(news, "category")),
                    () -> Identities.onSite(site, Identities.user(two), () -> {
                        PostSnapshot post = posts.create(PostChanges.builder()
                                .title("Racing")
                                .status("publish")
                                .build());
                        return terms.setPostTerms(post.id(), "category", List.of(TermRef.id(news)), false);
                    })));
            assertThat(results).noneMatch(Throwable.class::isInstance);
            TermId uncategorized = TestData.defaultCategory(dataSource, site);
            assertThat(Jdbc.count(dataSource, "select count(*) from qp_term where id = ?", news.value()))
                    .isZero();
            assertThat(Jdbc.count(dataSource, "select count from qp_term where id = ?", uncategorized.value()))
                    .isEqualTo(Jdbc.count(
                            dataSource,
                            "select count(*) from qp_term_assignment a join qp_post p on p.id = a.post_id"
                                    + " where a.term_id = ? and p.status = 'publish'",
                            uncategorized.value()));
        }
    }

    @Test
    void aPostSavedWhileItsCategoryIsDeletedWaitsForTheDelete() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId one = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, site, one, "[\"editor\"]");
        UserId two = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, site, two, "[\"editor\"]");
        TermId news = TestData.term(dataSource, site, "category", "News", "news", null);
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch saved = new CountDownLatch(1);
        pause.arm(deleting, saved);
        try {
            List<Object> results = Races.run(List.<Callable<Object>>of(
                    () -> Identities.onSite(site, Identities.user(one), () -> terms.delete(news, "category")), () -> {
                        assertThat(deleting.await(30, TimeUnit.SECONDS)).isTrue();
                        return Identities.onSite(site, Identities.user(two), () -> {
                            // the site's post lock holds this save until the delete has committed
                            PostSnapshot post = posts.create(PostChanges.builder()
                                    .title("Late")
                                    .status("publish")
                                    .build());
                            saved.countDown();
                            return terms.setPostTerms(post.id(), "category", List.of(TermRef.id(news)), false);
                        });
                    }));
            assertThat(results).noneMatch(Throwable.class::isInstance);
            assertThat(results.get(1)).isEqualTo(List.of());
        } finally {
            pause.disarm();
        }
        // the assignment found no term and replaced the post's categories with none
        TermId uncategorized = TestData.defaultCategory(dataSource, site);
        assertThat(Jdbc.count(dataSource, "select count from qp_term where id = ?", uncategorized.value()))
                .isZero();
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_term where id = ?", news.value()))
                .isZero();
    }
}
