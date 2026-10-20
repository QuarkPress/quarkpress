package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;

class CreateConcurrencyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(CreateConcurrencyDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    private Callable<Object> publisher(SiteId site, UserId user) {
        return () -> Identities.onSite(
                site,
                Identities.user(user),
                () -> posts.create(
                        PostChanges.builder().title("Hello").status("publish").build()));
    }

    private Callable<Object> systemPublisher(SiteId site) {
        return () -> Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(
                        PostChanges.builder().title("Hello").status("publish").build()));
    }

    @Test
    void concurrentPublicationsOfOneTitleGetDistinctSlugs() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        UserId other = TestData.memberOfMain(dataSource, "editor");
        List<Callable<Object>> writers = new ArrayList<>();
        writers.add(systemPublisher(site));
        writers.add(systemPublisher(site));
        writers.add(publisher(site, editor));
        writers.add(publisher(site, other));
        List<Object> results = Races.run(writers);
        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(PostSnapshot.class));
        assertThat(results)
                .extracting(result -> ((PostSnapshot) result).slug())
                .containsExactlyInAnyOrder("hello", "hello-2", "hello-3", "hello-4");
    }

    @Test
    void concurrentPublicationsOnSeveralSitesNeitherDeadlockNorShareSlugs() throws Exception {
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        for (int round = 0; round < 3; round++) {
            List<SiteId> sites = new ArrayList<>();
            List<Callable<Object>> writers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                SiteId site = TestData.site(dataSource);
                sites.add(site);
                writers.add(publisher(site, editor));
                writers.add(systemPublisher(site));
            }
            List<Object> results = Races.run(writers);
            assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(PostSnapshot.class));
            for (SiteId site : sites) {
                assertThat(results)
                        .map(result -> (PostSnapshot) result)
                        .filteredOn(post -> post.site().equals(site))
                        .extracting(PostSnapshot::slug)
                        .as("site %s", site.value())
                        .containsExactlyInAnyOrder("hello", "hello-2");
            }
        }
    }
}
