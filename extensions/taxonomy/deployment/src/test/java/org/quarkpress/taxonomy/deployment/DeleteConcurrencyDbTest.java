package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.taxonomy.TermRef;
import org.quarkpress.taxonomy.Terms;

/** A category deleted while another editor publishes a post into it: no deadlock, exact counts either way. */
class DeleteConcurrencyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.multisite(DeleteConcurrencyDbTest.class);

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
}
