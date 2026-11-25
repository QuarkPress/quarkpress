package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.front.runtime.Redirect;

/**
 * Attachment requests with attachment pages off (the default) go to the attachment's file, unless its parent is not
 * publicly viewable; with them on, to the attachment's page. WordPress's recorded answers: front-facts.json, ATTACH and
 * ATTACH-PAGES (G6 for the attachment of a draft).
 */
class AttachmentRedirectDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(
            AttachmentRedirectDbTest.class, RedirectCast.class, FrontCast.class, BookType.class, GenreTaxonomy.class);

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    static RedirectCast cast;

    @BeforeEach
    void cast() {
        if (cast == null) {
            cast = new RedirectCast(dataSource);
        }
        cast.reset();
    }

    private static Optional<Redirect> to(String location) {
        return Optional.of(new Redirect(301, location));
    }

    @Test
    void pagesOffGoToTheFile() throws Exception {
        String photo = cast.fileOf(cast.photo);
        assertThat(photo).endsWith("/2025/11/photo.jpg");
        assertThat(cast.canonical("/2025/11/23/second-post/photo/")).isEqualTo(to(photo));
        assertThat(cast.canonical("/?attachment_id=" + cast.photo.value())).isEqualTo(to(photo));
        assertThat(cast.canonical("/?attachment_id=" + cast.photo.value() + "&utm=1"))
                .isEqualTo(to(photo + "?utm=1"));
        assertThat(cast.canonical("/logo/")).isEqualTo(to(cast.fileOf(cast.logo)));
        assertThat(cast.canonical("/2025/11/20/hello-world/photo/")).isEqualTo(to(photo));
    }

    @Test
    void theAttachmentOfADraftIsNotRedirected() throws Exception {
        // anonymous: a 404 (the draft's attachment is hidden); its editor: the page, the parent being unreadable
        String target = "/?attachment_id=" + cast.draftShot.value();
        assertThat(cast.request(target, org.quarkword.front.Viewer.anonymous()).status())
                .isEqualTo(404);
        assertThat(cast.canonical(target)).isEmpty();
        assertThat(cast.request(target, RedirectCast.as(cast.alice)).status()).isEqualTo(200);
        assertThat(cast.canonical(target, RedirectCast.as(cast.alice))).isEmpty();
    }

    @Test
    void pagesOnGoToTheAttachmentPage() throws Exception {
        cast.option("wp_attachment_pages_enabled", "\"1\"");
        String page = "http://wp.test/2025/11/23/second-post/photo/";
        assertThat(cast.canonical("/?attachment_id=" + cast.photo.value())).isEqualTo(to(page));
        assertThat(cast.canonical("/2025/11/20/hello-world/photo/")).isEqualTo(to(page));
        assertThat(cast.canonical("/2025/11/23/second-post/photo/")).isEmpty();
        assertThat(cast.canonical("/logo/")).isEmpty();
    }
}
