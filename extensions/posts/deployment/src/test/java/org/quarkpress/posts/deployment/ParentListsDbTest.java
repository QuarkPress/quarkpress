package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;

/**
 * The parent lists as WordPress reads {@code post_parent__in} and {@code post_parent__not_in}: 0 stands for the top
 * level (no parent), alone or next to real parents.
 */
class ParentListsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(ParentListsDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    SiteId site;
    int day;
    PostId top;
    PostId other;
    PostId child;
    PostId grandchild;

    @BeforeEach
    void setUp() throws Exception {
        site = TestData.site(dataSource);
        day = 0;
        top = page("Top", null);
        other = page("Other", null);
        child = page("Child", top);
        grandchild = page("Grandchild", child);
    }

    private PostId page(String title, PostId parent) throws Exception {
        PostChanges.Builder changes = PostChanges.builder()
                .type("page")
                .title(title)
                .status("publish")
                .dateGmt(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(86_400L * ++day));
        if (parent != null) {
            changes.parent(parent);
        }
        PostChanges built = changes.build();
        return Identities.onSite(site, Identities.system(), () -> posts.create(built))
                .id();
    }

    private List<PostId> ids(PostQuery.Builder query) throws Exception {
        return Identities.onSite(
                        site,
                        Identities.anonymous(),
                        () -> posts.query(query.types("page").build()))
                .posts()
                .stream()
                .map(PostSnapshot::id)
                .toList();
    }

    @Test
    void zeroInTheParentListMeansTheTopLevel() throws Exception {
        assertThat(ids(PostQuery.builder().parentIn(0L))).containsExactlyInAnyOrder(top, other);
        assertThat(ids(PostQuery.builder().parentIn(0L, top.value()))).containsExactlyInAnyOrder(top, other, child);
        assertThat(ids(PostQuery.builder().parentIn(top.value()))).containsExactly(child);
        assertThat(ids(PostQuery.builder().parentIn(child).parentIn(0L)))
                .containsExactlyInAnyOrder(top, other, grandchild);
        // the list wins over the excluded parents
        assertThat(ids(PostQuery.builder().parentIn(0L).parentNotIn(0L))).containsExactlyInAnyOrder(top, other);
        assertThat(PostQuery.builder().parentIn(0L, top.value()).build().parentInTopLevel())
                .isTrue();
        assertThat(PostQuery.builder().parentIn(0L, top.value()).build().parentIn())
                .containsExactly(top);
    }

    @Test
    void zeroInTheExcludedParentsLeavesOutTheTopLevel() throws Exception {
        assertThat(ids(PostQuery.builder().parentNotIn(0L))).containsExactlyInAnyOrder(child, grandchild);
        assertThat(ids(PostQuery.builder().parentNotIn(0L, top.value()))).containsExactly(grandchild);
        assertThat(ids(PostQuery.builder().parentNotIn(top.value()))).containsExactlyInAnyOrder(top, other, grandchild);
        PostQuery query = PostQuery.builder().parentNotIn(0L).build();
        assertThat(query.parentNotInTopLevel()).isTrue();
        assertThat(query.toBuilder().build().parentNotInTopLevel()).isTrue();
        assertThat(ids(query.toBuilder())).containsExactlyInAnyOrder(child, grandchild);
    }
}
