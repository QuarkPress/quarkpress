package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PageTemplates;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.ProtectedMeta;

/** The page template ({@code _wp_page_template}) stored as given. */
class PageTemplatesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(PageTemplatesDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    PageTemplates templates;

    @Inject
    Posts posts;

    @Inject
    ProtectedMeta protectedMeta;

    @Test
    void theProtectedWriterAcceptsOnlyItsOwnKeys() {
        SiteId site = TestData.site(dataSource);
        assertThatThrownBy(() -> protectedMeta.put(site, PostEntity.create(site), "color", TextNode.valueOf("red")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a key of the protected meta writer: color");
        assertThatThrownBy(() -> protectedMeta.remove(site, PostEntity.create(site), "footnotes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a key of the protected meta writer: footnotes");
    }

    @Test
    void setStoresTheValueAsGivenAndAbsentReadsEmpty() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        UserId erin = TestData.memberOfMain(dataSource, "subscriber");
        PostId page = TestData.post(dataSource, site, "page", "publish", "about", "About");
        String version = Jdbc.strings(dataSource, "select version from qp_post where id = ?", page.value())
                .get(0);

        assertThat(of(site, editor, page)).isEmpty();
        set(site, editor, page, "page-no-title");
        assertThat(of(site, editor, page)).isEqualTo("page-no-title");
        assertThat(Jdbc.strings(dataSource, "select meta from qp_post where id = ?", page.value()))
                .containsExactly("{\"_wp_page_template\":[\"page-no-title\"]}");
        set(site, editor, page, "");
        assertThat(of(site, editor, page)).isEmpty();
        assertThat(Jdbc.strings(dataSource, "select meta from qp_post where id = ?", page.value()))
                .containsExactly("{\"_wp_page_template\":[\"\"]}");
        assertThat(Jdbc.strings(dataSource, "select version from qp_post where id = ?", page.value()))
                .containsExactly(version);

        assertThatThrownBy(() -> set(site, erin, page, "page-no-title"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability edit_post on site " + site.value());
    }

    private void set(SiteId site, UserId user, PostId post, String template) throws Exception {
        Identities.onSite(site, Identities.user(user), () -> {
            templates.set(post, template);
            return null;
        });
    }

    private String of(SiteId site, UserId user, PostId post) throws Exception {
        return Identities.onSite(
                site,
                Identities.user(user),
                () -> PageTemplates.of(posts.find(post).orElseThrow()));
    }
}
