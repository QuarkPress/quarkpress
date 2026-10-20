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
import org.quarkpress.persistence.runtime.HeldLocks;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;

/** Updating checks what WordPress's REST layer checks, before the site's post lock is taken. */
class UpdateCapabilityDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(UpdateCapabilityDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    SiteId site;
    UserId contributor;
    UserId author;
    UserId editor;

    @BeforeEach
    void setUp() {
        site = TestData.site(dataSource);
        contributor = TestData.memberOfMain(dataSource, "contributor");
        author = TestData.memberOfMain(dataSource, "author");
        editor = TestData.memberOfMain(dataSource, "editor");
    }

    private PostSnapshot as(UserId user, PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(user), () -> posts.create(changes.build()));
    }

    private PostSnapshot update(UserId user, PostSnapshot post, PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(user), () -> posts.update(post.id(), changes.build()));
    }

    private String lacks(String capability) {
        return "the caller lacks the capability " + capability + " on site " + site.value();
    }

    private String title(PostSnapshot post) {
        return Jdbc.strings(
                        dataSource,
                        "select title from qp_post where id = ?",
                        post.id().value())
                .get(0);
    }

    @Test
    void contributorsEditTheirDraftsButNeitherPublishNorEditPublishedPosts() throws Exception {
        PostSnapshot draft = as(contributor, PostChanges.builder().title("Mine"));
        assertThat(update(contributor, draft, PostChanges.builder().title("Still mine"))
                        .title())
                .isEqualTo("Still mine");
        assertThatThrownBy(
                        () -> update(contributor, draft, PostChanges.builder().status("publish")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("publish_posts"));
        PostSnapshot published = as(
                editor, PostChanges.builder().title("Theirs").status("publish").author(contributor));
        assertThatThrownBy(() ->
                        update(contributor, published, PostChanges.builder().title("Changed")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_post"));
        assertThat(title(draft)).isEqualTo("Still mine");
        assertThat(title(published)).isEqualTo("Theirs");
    }

    @Test
    void authorsPublishTheirOwnPostsButNotOthers() throws Exception {
        PostSnapshot own = as(author, PostChanges.builder().title("Own"));
        assertThat(update(author, own, PostChanges.builder().status("publish")).status())
                .isEqualTo("publish");
        PostSnapshot editors = as(editor, PostChanges.builder().title("Editors"));
        assertThatThrownBy(() -> update(author, editors, PostChanges.builder().title("Taken")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_post"));
        assertThatThrownBy(() -> update(author, own, PostChanges.builder().author(editor)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_others_posts"));
        assertThat(update(editor, own, PostChanges.builder().author(editor)).author())
                .contains(editor);
    }

    @Test
    void changingTheTypeNeedsTheNewTypesCreateCapability() throws Exception {
        PostSnapshot draft = as(contributor, PostChanges.builder().title("Mine"));
        assertThatThrownBy(
                        () -> update(contributor, draft, PostChanges.builder().type("page")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_pages"));
        assertThat(Jdbc.strings(
                        dataSource,
                        "select type from qp_post where id = ?",
                        draft.id().value()))
                .containsExactly("post");
        assertThat(update(contributor, draft, PostChanges.builder().type("post").title("Same type"))
                        .type())
                .isEqualTo("post");
        assertThat(update(contributor, draft, PostChanges.builder().type("thing"))
                        .type())
                .isEqualTo("thing");
        PostSnapshot own = as(editor, PostChanges.builder().title("Editors"));
        assertThat(update(editor, own, PostChanges.builder().type("page")).type())
                .isEqualTo("page");
    }

    @Test
    void anonymousCallersAreChallenged() throws Exception {
        PostSnapshot post = as(editor, PostChanges.builder().title("Post"));
        assertThatThrownBy(() -> Identities.onSite(
                        site, Identities.anonymous(), () -> posts.update(post.id(), PostChanges.none())))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(lacks("edit_post"));
        assertThatThrownBy(() -> Identities.onSite(site, Identities.anonymous(), () -> posts.publish(post.id())))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(lacks("publish_post"));
    }

    @Test
    void capabilitiesAreCheckedBeforeTheSitesPostLock() throws Exception {
        PostSnapshot post = as(editor, PostChanges.builder().title("Post").status("publish"));
        SiteId other = TestData.site(dataSource);
        assertThatThrownBy(() -> Identities.onSite(other, Identities.system(), () -> {
                    HeldLocks.record("qp_post_lock:" + site.value());
                    return update(contributor, post, PostChanges.builder().title("Refused"));
                }))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_post"));
        assertThatThrownBy(() -> Identities.onSite(other, Identities.system(), () -> {
                    HeldLocks.record("qp_post_lock:" + site.value());
                    return Identities.onSite(site, Identities.user(contributor), () -> posts.publish(post.id()));
                }))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("publish_post"));
        assertThatThrownBy(() -> Identities.onSite(other, Identities.system(), () -> {
                    HeldLocks.record("qp_post_lock:" + site.value());
                    return update(editor, post, PostChanges.builder().title("Waiting"));
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the posts of site " + site.value() + " are locked by a transaction that waits for this"
                        + " change: change the posts of one site in one transaction at a time");
        assertThat(title(post)).isEqualTo("Post");
    }

    @Test
    void aGivenAuthorsProfileIsLockedBeforeTheSitesPostLock() throws Exception {
        PostSnapshot post = as(editor, PostChanges.builder().title("Post"));
        SiteId other = TestData.site(dataSource);
        assertThatThrownBy(() -> Identities.onSite(other, Identities.system(), () -> {
                    HeldLocks.record("qp_user_profile:" + author.value());
                    HeldLocks.record("qp_post_lock:" + site.value());
                    return update(
                            editor, post, PostChanges.builder().title("Held").author(author));
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the profile of user " + author.value()
                        + " is locked by a transaction that waits for this post write: write the post in a separate"
                        + " transaction");
        assertThat(title(post)).isEqualTo("Post");
        assertThat(update(editor, post, PostChanges.builder().author(author)).author())
                .contains(author);
    }
}
