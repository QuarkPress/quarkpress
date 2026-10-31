package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.MemberPage;
import org.quarkpress.security.MemberQuery;
import org.quarkpress.security.Memberships;

/**
 * The security module's published-authors filter, which reads the posts module's table. On a network, where the main
 * site's members are exactly the users with a membership row there (a single site lists every user).
 */
class MembersWithPublishedPostsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.multisite(MembersWithPublishedPostsDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Memberships memberships;

    UserId bobId;
    UserId carolId;
    UserId daveId;
    UserId erinId;

    @BeforeEach
    void members() {
        // the main site has exactly these members
        Jdbc.update(dataSource, "delete from qp_site_membership where site_id = ?", TestData.MAIN.value());
        bobId = member("bob", "editor");
        carolId = member("carol", "author");
        daveId = member("dave", "contributor");
        erinId = member("erin", "subscriber");
        postBy(TestData.MAIN, carolId, "post", "publish");
        postBy(TestData.MAIN, daveId, "post", "draft");
        postBy(TestData.MAIN, daveId, "attachment", "inherit");
        postBy(TestData.MAIN, erinId, "post", "private");
        postBy(TestData.MAIN, bobId, "page", "publish");
    }

    /** A member of the main site whose display name sorts as {@code name}. */
    private UserId member(String name, String role) {
        UserId user = TestData.profile(dataSource, name);
        TestData.member(dataSource, TestData.MAIN, user, "[\"" + role + "\"]");
        return user;
    }

    private void postBy(SiteId site, UserId author, String type, String status) {
        PostId post = TestData.post(dataSource, site, type, status, "", type + " " + status);
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", author.value(), post.value());
    }

    private MemberPage authors(MemberQuery.Builder query) {
        return memberships.members(TestData.MAIN, query.build());
    }

    @Test
    void onlyAuthorsOfAPublishedPostOfTheTypesAreListed() {
        MemberPage posts = authors(MemberQuery.builder().publishedPostTypes(List.of("post")));
        assertThat(posts.users()).containsExactly(carolId);
        assertThat(posts.total()).isEqualTo(1);
        MemberPage postsAndPages = authors(MemberQuery.builder().publishedPostTypes(List.of("post", "page")));
        assertThat(postsAndPages.users()).containsExactly(bobId, carolId);
        assertThat(postsAndPages.total()).isEqualTo(2);
        assertThat(authors(MemberQuery.builder()
                                .publishedPostTypes(List.of("post", "page"))
                                .exclude(List.of(bobId)))
                        .users())
                .containsExactly(carolId);
        assertThat(authors(MemberQuery.builder().publishedPostTypes(List.of("attachment")))
                        .users())
                .isEmpty();
    }

    @Test
    void aPublishedPostOnAnotherSiteDoesNotMakeAnAuthorOfThisSite() {
        SiteId other = TestData.site(dataSource);
        UserId frankId = member("frank", "author");
        TestData.member(dataSource, other, carolId, "[\"author\"]");
        TestData.member(dataSource, other, frankId, "[\"author\"]");
        postBy(other, frankId, "post", "publish");
        postBy(other, carolId, "post", "publish");
        Jdbc.update(
                dataSource,
                "delete from qp_post where site_id = ? and author_id = ?",
                TestData.MAIN.value(),
                carolId.value());
        MemberPage page = authors(MemberQuery.builder().publishedPostTypes(List.of("post")));
        assertThat(page.users()).isEmpty();
        assertThat(page.total()).isZero();
    }
}
