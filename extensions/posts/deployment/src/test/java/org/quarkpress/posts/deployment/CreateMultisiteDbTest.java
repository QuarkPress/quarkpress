package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;

/** In a network only network administrators save unfiltered HTML; site editors and administrators are filtered. */
class CreateMultisiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.multisite(CreateMultisiteDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Test
    void siteEditorsAreFilteredAndNetworkAdministratorsAreNot() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, site, editor, "[\"administrator\"]");
        UserId admin = TestData.profile(dataSource, "network-admin");
        Jdbc.update(dataSource, "insert into qp_network_admin (user_id) values (?)", admin.value());
        String css = "<!-- wp:paragraph {\"style\":{\"css\":\"x\"}} --><p onclick=\"x()\">Hi</p><!-- /wp:paragraph -->";
        PostSnapshot bySiteAdmin = Identities.onSite(
                site,
                Identities.user(editor),
                () -> posts.create(
                        PostChanges.builder().title("<b>News</b>").content(css).build()));
        assertThat(bySiteAdmin.title()).isEqualTo("<b>News</b>");
        assertThat(bySiteAdmin.content()).isEqualTo("<!-- wp:paragraph --><p>Hi</p><!-- /wp:paragraph -->");
        PostSnapshot byNetworkAdmin = Identities.onSite(
                site,
                Identities.user(admin),
                () -> posts.create(
                        PostChanges.builder().title("Raw").content(css).build()));
        assertThat(byNetworkAdmin.content()).isEqualTo(css);
    }
}
