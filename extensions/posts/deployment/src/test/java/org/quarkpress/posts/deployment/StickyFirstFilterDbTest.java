package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostQueryFilter;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.QueryContext;

/** WordPress decides whether sticky posts go first before its query filters run, so a filter cannot turn it off. */
class StickyFirstFilterDbTest {

    /** Adds a search to every query, as a {@code pre_get_posts} callback could. */
    @ApplicationScoped
    static class AddsSearch implements PostQueryFilter {
        @Override
        public PostQuery apply(PostQuery query, QueryContext context) {
            return query.toBuilder().search("post").build();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(StickyFirstFilterDbTest.class, AddsSearch.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Test
    void aSearchAddedByAFilterKeepsStickyPostsFirst() throws Exception {
        SiteId site = TestData.site(dataSource);
        List<PostId> p = new ArrayList<>();
        p.add(null);
        for (int i = 1; i <= 6; i++) {
            PostChanges changes = PostChanges.builder()
                    .title("Post " + i)
                    .status("publish")
                    .dateGmt(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(86_400L * i))
                    .build();
            p.add(Identities.onSite(site, Identities.system(), () -> posts.create(changes))
                    .id());
        }
        Identities.onSite(site, Identities.system(), () -> posts.stick(p.get(2)));
        List<PostId> found = Identities.onSite(
                        site,
                        Identities.anonymous(),
                        () -> posts.query(
                                PostQuery.builder().perPage(4).stickyFirst(true).build()))
                .posts()
                .stream()
                .map(PostSnapshot::id)
                .toList();
        assertThat(found).containsExactly(p.get(2), p.get(6), p.get(5), p.get(4), p.get(3));
    }
}
