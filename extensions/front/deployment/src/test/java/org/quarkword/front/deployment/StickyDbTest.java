package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkword.front.deployment.FrontCast.ids;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.front.FrontRequest;

/** Sticky posts on the home list and the posts page, and nowhere else. */
class StickyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(StickyDbTest.class, FrontCast.class);

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    static FrontCast cast;

    @BeforeEach
    void cast() {
        if (cast == null) {
            cast = new FrontCast(dataSource);
        }
        cast.dropOption("posts_per_page");
        cast.unstick(cast.p9);
        cast.option("show_on_front", "\"posts\"");
        cast.option("page_on_front", "0");
        cast.option("page_for_posts", "0");
    }

    @Test
    void theStickyPostLeadsTheHomeList() throws Exception {
        FrontRequest home = cast.get("");
        assertThat(home.flags().home()).isTrue();
        assertThat(ids(home)).containsExactly(cast.p7, cast.p10, cast.p9, cast.p8, cast.p3, cast.p2, cast.p1);
    }

    @Test
    void aStickyPostFromALaterPageIsAddedAndRepeats() throws Exception {
        cast.option("posts_per_page", "3");
        FrontRequest first = cast.get("");
        assertThat(ids(first)).containsExactly(cast.p7, cast.p10, cast.p9, cast.p8);
        assertThat(first.posts().found()).isEqualTo(7);
        assertThat(first.posts().maxPages()).isEqualTo(3);
        assertThat(ids(cast.get("paged=2"))).containsExactly(cast.p3, cast.p2, cast.p1);
        assertThat(ids(cast.get("paged=3"))).containsExactly(cast.p7);
    }

    @Test
    void stickyPostsMoveInFetchedOrder() throws Exception {
        cast.sticky(cast.p9);
        assertThat(ids(cast.get(""))).containsExactly(cast.p9, cast.p7, cast.p10, cast.p8, cast.p3, cast.p2, cast.p1);
    }

    @Test
    void thePostsPageIsTheHomeList() throws Exception {
        cast.option("show_on_front", "\"page\"");
        cast.option("page_on_front", Long.toString(cast.home22.value()));
        cast.option("page_for_posts", Long.toString(cast.blog21.value()));
        FrontRequest blog = cast.get("pagename=blog");
        assertThat(blog.flags().postsPage()).isTrue();
        assertThat(ids(blog)).containsExactly(cast.p7, cast.p10, cast.p9, cast.p8, cast.p3, cast.p2, cast.p1);
    }

    @Test
    void archivesAndSearchesKeepTheDateOrder() throws Exception {
        assertThat(ids(cast.get("cat=" + cast.uncategorized.value()))).containsExactly(cast.p10, cast.p1, cast.p7);
        assertThat(ids(cast.get("s=welcome"))).containsExactly(cast.p7);
        assertThat(ids(cast.get("year=2026"))).containsExactly(cast.p10, cast.p9, cast.p8, cast.p3, cast.p2, cast.p1);
    }
}
