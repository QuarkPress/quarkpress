package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostQueryFilter;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.QueryContext;

class QueryFilterDbTest {

    /**
     * Turns a query for "pages-instead" titles into a query for pages, and one for "search-added" titles into a search
     * for "page".
     */
    @ApplicationScoped
    static class PagesInstead implements PostQueryFilter {
        static final AtomicReference<QueryContext> SEEN = new AtomicReference<>();

        @Override
        public PostQuery apply(PostQuery query, QueryContext context) {
            SEEN.set(context);
            if (query.title().filter("search-added"::equals).isPresent()) {
                return query.toBuilder().clearTitle().search("page").build();
            }
            return query.title().filter("pages-instead"::equals).isPresent()
                    ? query.toBuilder().clearTitle().types("page").build()
                    : query;
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(QueryFilterDbTest.class, PagesInstead.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Test
    void queryFiltersChangeTheQueryFirst() throws Exception {
        SiteId site = TestData.site(dataSource);
        PostSnapshot page = Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(PostChanges.builder()
                        .title("A page")
                        .type("page")
                        .status("publish")
                        .build()));
        assertThat(Identities.onSite(
                                site,
                                Identities.anonymous(),
                                () -> posts.query(PostQuery.builder()
                                        .title("pages-instead")
                                        .build()))
                        .posts())
                .extracting(PostSnapshot::id)
                .containsExactly(page.id());
        assertThat(PagesInstead.SEEN.get()).isEqualTo(new QueryContext(site, Optional.empty()));
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        Identities.onSite(
                site,
                Identities.user(editor),
                () -> posts.query(PostQuery.builder().build()));
        assertThat(PagesInstead.SEEN.get()).isEqualTo(new QueryContext(site, Optional.of(editor)));
    }

    @Test
    void aSearchAddedByAFilterKeepsTheDefaultTypeOfTheRequestedQuery() throws Exception {
        SiteId site = TestData.site(dataSource);
        Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(PostChanges.builder()
                        .title("A page")
                        .type("page")
                        .status("publish")
                        .build()));
        PostSnapshot post = Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(PostChanges.builder()
                        .title("A post about a page")
                        .status("publish")
                        .build()));
        assertThat(Identities.onSite(
                                site,
                                Identities.anonymous(),
                                () -> posts.query(PostQuery.builder()
                                        .title("search-added")
                                        .build()))
                        .posts())
                .extracting(PostSnapshot::id)
                .containsExactly(post.id());
    }
}
