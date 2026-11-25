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
import org.quarkword.core.PostId;
import org.quarkword.front.Viewer;
import org.quarkword.front.runtime.Redirect;

/**
 * WordPress's old slug and old date redirect, decided before the canonical one, over the routing reference's cast
 * (post hello-world was {@code hello}, second-post was dated 2025-11-01, book dune was {@code old-dune}). The
 * expectations are WordPress's recorded answers (front-facts.json, G17 and G17-trash).
 */
class OldSlugRedirectsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(
            OldSlugRedirectsDbTest.class, RedirectCast.class, FrontCast.class, BookType.class, GenreTaxonomy.class);

    static final String W = "http://wp.test";
    static final String HELLO = W + "/2025/11/20/hello-world/";

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
    void anOldSlugOrAnOldDay() throws Exception {
        assertThat(cast.oldSlug("/2025/11/20/hello/")).isEqualTo(to(HELLO));
        assertThat(cast.oldSlug("/2025/11/01/second-post/")).isEqualTo(to(W + "/2025/11/23/second-post/"));
        // a year and a slug is a page path: no old slug lookup, but the guess finds the post (G17)
        assertThat(cast.oldSlug("/2025/hello/")).isEmpty();
        assertThat(cast.redirect("/2025/hello/")).isEqualTo(to(HELLO));
        assertThat(cast.oldSlug("/2025/11/20/hello/page/2/")).isEqualTo(to(HELLO + "page/2/"));
        assertThat(cast.oldSlug("/books/old-dune/")).isEqualTo(to(W + "/books/dune/"));
        assertThat(cast.oldSlug("/?book=old-dune")).isEqualTo(to(W + "/books/dune/"));
        // the old slug on another day, and no old day: nothing, and the guess on that day finds nothing either
        assertThat(cast.oldSlug("/2025/11/01/hello/")).isEmpty();
        assertThat(cast.redirect("/2025/11/01/hello/")).isEmpty();
    }

    @Test
    void pagesAreNeverRedirectedByOldSlug() throws Exception {
        // a page path: the type is page, hierarchical; the canonical guess answers instead
        assertThat(cast.oldSlug("/abou/")).isEmpty();
        assertThat(cast.oldSlug("/hello/")).isEmpty();
        assertThat(cast.redirect("/hello/")).isEqualTo(to(HELLO));
        assertThat(cast.redirect("/abou/")).isEqualTo(to(W + "/about/"));
    }

    @Test
    void foundRequestsAreNeverRedirected() throws Exception {
        assertThat(cast.oldSlug("/2025/11/20/hello-world/")).isEmpty();
        assertThat(cast.oldSlug("/books/dune/")).isEmpty();
    }

    @Test
    void underThePostnameStructure() throws Exception {
        cast.structure("/%postname%/");
        assertThat(cast.oldSlug("/hello/")).isEqualTo(to(W + "/hello-world/"));
    }

    @Test
    void aPrivatePostsOldSlugAsTheViewerSeesIt() throws Exception {
        // the target is the post's permalink as the viewer sees it: plain for a visitor who may not read the private
        // post, pretty for its author (WordPress's get_permalink, FR7.2), whatever the request's own identity
        String plain = W + "/?p=" + cast.secret.value();
        String pretty = W + "/2025/11/22/secret/";
        assertThat(cast.oldSlug("/2025/11/22/old-secret/")).isEqualTo(to(plain));
        assertThat(cast.oldSlug("/2025/11/22/old-secret/", RedirectCast.as(cast.alice)))
                .isEqualTo(to(pretty));
        assertThat(cast.oldSlug("/2025/11/22/old-secret/", Viewer.anonymous(), RedirectCast.as(cast.alice)))
                .isEqualTo(to(plain));
        assertThat(cast.oldSlug("/2025/11/22/old-secret/", RedirectCast.as(cast.alice), Viewer.anonymous()))
                .isEqualTo(to(pretty));
    }

    @Test
    void noStatusIsChecked() throws Exception {
        // a trashed post keeps its old slug: the old URL goes to its plain link, its last URL is a 404 (G17-trash)
        RedirectCast trashed = new RedirectCast(dataSource);
        PostId post = trashed.hello;
        Jdbc.update(
                dataSource,
                "update qw_post set status = 'trash', slug = 'hello-world__trashed' where id = ?",
                post.value());
        assertThat(trashed.oldSlug("/2025/11/20/hello/")).isEqualTo(to(W + "/?p=" + post.value()));
        assertThat(trashed.redirect("/2025/11/20/hello-world/")).isEmpty();
    }
}
