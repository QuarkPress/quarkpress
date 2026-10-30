package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostAuthors;

/** Whether an author has posts the caller may count, as WordPress's count_user_posts counts them for REST. */
class PostAuthorsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(PostAuthorsDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    PostAuthors authors;

    UserId editorId;
    UserId carolId;
    UserId daveId;
    UserId erinId;

    @BeforeEach
    void members() {
        editorId = TestData.memberOfMain(dataSource, "editor");
        carolId = TestData.memberOfMain(dataSource, "author");
        daveId = TestData.memberOfMain(dataSource, "contributor");
        erinId = TestData.memberOfMain(dataSource, "subscriber");
        postBy(TestData.MAIN, carolId, "post", "publish");
        postBy(TestData.MAIN, daveId, "post", "draft");
        postBy(TestData.MAIN, erinId, "post", "private");
    }

    private void postBy(SiteId site, UserId author, String type, String status) {
        PostId post = TestData.post(dataSource, site, type, status, "", type + " " + status);
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", author.value(), post.value());
    }

    private boolean visible(SecurityIdentity caller, UserId author, List<String> types) throws Exception {
        return Identities.onSite(TestData.MAIN, caller, () -> authors.hasPostsVisibleToCaller(author, types));
    }

    @Test
    void aPrivatePostCountsForItsAuthorAndForPrivateReaders() throws Exception {
        assertThat(visible(Identities.user(editorId), erinId, List.of("post"))).isTrue();
        assertThat(visible(Identities.user(erinId), erinId, List.of("post"))).isTrue();
        assertThat(visible(Identities.user(carolId), erinId, List.of("post"))).isFalse();
        assertThat(visible(Identities.anonymous(), erinId, List.of("post"))).isFalse();
    }

    @Test
    void aPublishedPostCountsForAnyone() throws Exception {
        assertThat(visible(Identities.anonymous(), carolId, List.of("post"))).isTrue();
        assertThat(visible(Identities.anonymous(), carolId, List.of("page"))).isFalse();
    }

    @Test
    void aDraftNeverCounts() throws Exception {
        assertThat(visible(Identities.anonymous(), daveId, List.of("post", "page")))
                .isFalse();
        assertThat(visible(Identities.user(carolId), daveId, List.of("post", "page")))
                .isFalse();
        assertThat(visible(Identities.user(editorId), daveId, List.of("post", "page")))
                .isFalse();
    }

    @Test
    void onlyRegisteredTypesCount() throws Exception {
        UserId frank = TestData.memberOfMain(dataSource, "author");
        // another site's post never counts; the site filter of every query guards that besides the query's own clause
        postBy(TestData.site(dataSource), frank, "post", "publish");
        postBy(TestData.MAIN, frank, "no_such_type", "publish");
        assertThat(visible(Identities.anonymous(), frank, List.of("post", "no_such_type")))
                .isFalse();
        assertThat(visible(Identities.anonymous(), frank, List.of())).isFalse();
    }
}
