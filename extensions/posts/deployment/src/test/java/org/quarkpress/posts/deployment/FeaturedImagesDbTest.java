package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.FeaturedImages;
import org.quarkpress.posts.Posts;

/** The featured image ({@code _thumbnail_id}) as WordPress's REST write sets it. */
class FeaturedImagesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(FeaturedImagesDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    FeaturedImages images;

    @Inject
    Posts posts;

    @Test
    void setFollowsWordPress() throws Exception {
        SiteId site = TestData.site(dataSource);
        SiteId other = TestData.site(dataSource);
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        UserId erin = TestData.memberOfMain(dataSource, "subscriber");
        PostId post = TestData.post(dataSource, site, "post", "draft", "", "Pictured");
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", editor.value(), post.value());
        PostId attachment = TestData.post(dataSource, site, "attachment", "inherit", "", "Picture");
        PostId page = TestData.post(dataSource, site, "page", "publish", "about", "About");
        PostId elsewhere = TestData.post(dataSource, other, "attachment", "inherit", "", "Elsewhere");
        List<String> before = versionAndModified(post);

        assertThat(of(site, editor, post)).isZero();
        set(site, editor, post, attachment.value());
        assertThat(of(site, editor, post)).isEqualTo(attachment.value());
        set(site, editor, post, 999_999);
        assertThat(of(site, editor, post)).isEqualTo(attachment.value());
        set(site, editor, post, elsewhere.value());
        assertThat(of(site, editor, post)).isEqualTo(attachment.value());
        set(site, editor, post, page.value());
        assertThat(of(site, editor, post)).isZero();
        assertThat(Jdbc.strings(dataSource, "select meta from qp_post where id = ?", post.value()))
                .containsExactly("{}");
        set(site, editor, post, -attachment.value());
        assertThat(of(site, editor, post)).isEqualTo(attachment.value());
        set(site, editor, post, 0);
        assertThat(of(site, editor, post)).isZero();
        assertThat(versionAndModified(post)).isEqualTo(before);

        assertThatThrownBy(() -> set(site, erin, post, attachment.value()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability edit_post on site " + site.value());
    }

    private void set(SiteId site, UserId user, PostId post, long attachment) throws Exception {
        Identities.onSite(site, Identities.user(user), () -> {
            images.set(post, attachment);
            return null;
        });
    }

    private long of(SiteId site, UserId user, PostId post) throws Exception {
        return Identities.onSite(
                site,
                Identities.user(user),
                () -> FeaturedImages.of(posts.find(post).orElseThrow()));
    }

    private List<String> versionAndModified(PostId post) {
        return List.of(
                Jdbc.strings(dataSource, "select version from qp_post where id = ?", post.value())
                        .get(0),
                Jdbc.strings(dataSource, "select modified_at from qp_post where id = ?", post.value())
                        .get(0));
    }
}
