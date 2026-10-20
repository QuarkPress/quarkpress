package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;

/** Creating checks what WordPress's REST layer checks: create, publish and others' posts capabilities. */
class CreateCapabilityDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(CreateCapabilityDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    SiteId site;

    @BeforeEach
    void setUp() {
        site = TestData.site(dataSource);
    }

    private PostSnapshot create(UserId user, PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(user), () -> posts.create(changes.build()));
    }

    private String lacks(String capability) {
        return "the caller lacks the capability " + capability + " on site " + site.value();
    }

    @Test
    void subscribersAndAnonymousCallersCannotCreate() {
        UserId subscriber = TestData.memberOfMain(dataSource, "subscriber");
        assertThatThrownBy(() -> create(subscriber, PostChanges.builder().title("No")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_posts"));
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.anonymous(),
                        () -> posts.create(PostChanges.builder().title("No").build())))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(lacks("edit_posts"));
        assertThatThrownBy(() ->
                        create(subscriber, PostChanges.builder().title("Page").type("page")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_pages"));
    }

    @Test
    void contributorsWriteDraftsAndPendingPostsButCannotPublish() throws Exception {
        UserId contributor = TestData.memberOfMain(dataSource, "contributor");
        assertThat(create(contributor, PostChanges.builder().title("Draft")).status())
                .isEqualTo("draft");
        assertThat(create(contributor, PostChanges.builder().title("Review").status("pending"))
                        .status())
                .isEqualTo("pending");
        for (String status : new String[] {"publish", "future", "private", "Publish"}) {
            assertThatThrownBy(() -> create(
                            contributor, PostChanges.builder().title("Out").status(status)))
                    .as(status)
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage(lacks("publish_posts"));
        }
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_post where site_id = ?", site.value()))
                .isEqualTo(2);
    }

    @Test
    void writingForAnotherAuthorNeedsEditOthersPosts() throws Exception {
        UserId author = TestData.memberOfMain(dataSource, "author");
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        assertThat(create(
                                author,
                                PostChanges.builder()
                                        .title("Mine")
                                        .status("publish")
                                        .author(author))
                        .status())
                .isEqualTo("publish");
        assertThatThrownBy(() ->
                        create(author, PostChanges.builder().title("Yours").author(editor)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_others_posts"));
        assertThatThrownBy(() ->
                        create(author, PostChanges.builder().title("Nobody's").author(0)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_others_posts"));
        assertThat(create(editor, PostChanges.builder().title("Theirs").author(author))
                        .author())
                .contains(author);
    }

    @Test
    void aTypeThatIsNotRegisteredIsCheckedWithThePostNames() throws Exception {
        UserId contributor = TestData.memberOfMain(dataSource, "contributor");
        assertThat(create(contributor, PostChanges.builder().title("Thing").type("thing"))
                        .type())
                .isEqualTo("thing");
        UserId subscriber = TestData.memberOfMain(dataSource, "subscriber");
        assertThatThrownBy(() ->
                        create(subscriber, PostChanges.builder().title("Thing").type("thing")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_posts"));
        assertThatThrownBy(() -> create(
                        contributor,
                        PostChanges.builder().title("Thing").type("thing").status("publish")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("publish_posts"));
    }
}
