package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.front.Viewer;

/**
 * The headers of front-end answers as WordPress sends them (front-facts.json G15, G16, FR15-page, FQ-G3, FR16-error;
 * no {@code X-Pingback}), with the preview headers QuarkWord adds and a configured {@code Cache-Control} for anonymous
 * pages.
 */
class FrontHeadersDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(
                    FrontHeadersDbTest.class, RedirectCast.class, FrontCast.class, BookType.class, GenreTaxonomy.class)
            .overrideConfigKey("quarkword.front.cache-control", "public, max-age=60");

    static final String NO_CACHE = "no-cache, must-revalidate, max-age=0, no-store, private";
    static final String EXPIRES = "Wed, 11 Jan 1984 05:00:00 GMT";
    static final String HTML = "text/html; charset=UTF-8";
    static final String API = "<http://wp.test/wp-json/>; rel=\"https://api.w.org/\"";

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

    private static String alternate(String route) {
        return "<http://wp.test/wp-json/wp/v2/" + route
                + ">; rel=\"alternate\"; title=\"JSON\"; type=\"application/json\"";
    }

    private static String shortlink(long id) {
        return "<http://wp.test/?p=" + id + ">; rel=shortlink";
    }

    @Test
    void aNotFoundPage() throws Exception {
        assertThat(cast.headers("/nope/", Viewer.anonymous(), false))
                .containsExactly(
                        entry("Expires", List.of(EXPIRES)),
                        entry("Cache-Control", List.of(NO_CACHE)),
                        entry("Content-Type", List.of(HTML)),
                        entry("Link", List.of(API)));
    }

    @Test
    void anonymousPagesTakeTheConfiguredCacheControl() throws Exception {
        long hello = cast.hello.value();
        assertThat(cast.headers("/2025/11/20/hello-world/", Viewer.anonymous(), false))
                .containsExactly(
                        entry("Content-Type", List.of(HTML)),
                        entry("Cache-Control", List.of("public, max-age=60")),
                        entry("Link", List.of(API, alternate("posts/" + hello), shortlink(hello))));
        assertThat(cast.headers("/about/", Viewer.anonymous(), false).get("Link"))
                .containsExactly(API, alternate("pages/" + cast.about.value()), shortlink(cast.about.value()));
        // the test's book type is not in the REST API: no alternate route
        assertThat(cast.headers("/books/dune/", Viewer.anonymous(), false).get("Link"))
                .containsExactly(API, shortlink(cast.dune.value()));
        assertThat(cast.headers("/category/news/", Viewer.anonymous(), false).get("Link"))
                .containsExactly(API, alternate("categories/" + cast.news.value()));
        assertThat(cast.headers("/author/alice-" + cast.site.value() + "/", Viewer.anonymous(), false)
                        .get("Link"))
                .containsExactly(API, alternate("users/" + cast.alice.value()));
        assertThat(cast.headers("/", Viewer.anonymous(), false).get("Link")).containsExactly(API);
    }

    @Test
    void theStaticFrontPagesShortlinkIsTheHome() throws Exception {
        cast.option("show_on_front", "\"page\"");
        cast.option("page_on_front", Long.toString(cast.sample.value()));
        assertThat(cast.headers("/", Viewer.anonymous(), false).get("Link"))
                .containsExactly(API, alternate("pages/" + cast.sample.value()), "<http://wp.test/>; rel=shortlink");
    }

    @Test
    void loggedInPasswordProtectedAndParseTimeNotFound() throws Exception {
        Map<String, List<String>> alice = cast.headers("/", RedirectCast.as(cast.alice), false);
        assertThat(alice.get("Cache-Control")).containsExactly(NO_CACHE);
        assertThat(alice.get("Expires")).containsExactly(EXPIRES);
        assertThat(cast.headers("/2025/11/20/hello-world/", Viewer.anonymous(), true)
                        .get("Cache-Control"))
                .containsExactly(NO_CACHE);
        // a 404 found by the parse itself is a 200 with the not-found page: no no-cache set (FQ-G2, FQ-G3)
        Map<String, List<String>> impossible = cast.headers("/2025/02/30/", Viewer.anonymous(), false);
        assertThat(cast.request("/2025/02/30/", Viewer.anonymous()).status()).isEqualTo(200);
        assertThat(impossible.get("Cache-Control")).containsExactly("public, max-age=60");
        assertThat(impossible).doesNotContainKey("Expires");
    }

    @Test
    void aPreview() throws Exception {
        Map<String, List<String>> preview =
                cast.headers("/?p=" + cast.draft.value(), RedirectCast.as(cast.alice), false);
        assertThat(preview)
                .containsEntry("Cache-Control", List.of(NO_CACHE))
                .containsEntry("Expires", List.of(EXPIRES))
                .containsEntry("X-Robots-Tag", List.of("noindex, nofollow"))
                .containsEntry("Content-Security-Policy", List.of("frame-ancestors 'self'"));
        assertThat(preview.get("Link"))
                .containsExactly(API, alternate("posts/" + cast.draft.value()), shortlink(cast.draft.value()));
    }

    @Test
    void redirects() throws Exception {
        String hello = "http://wp.test/2025/11/20/hello-world/";
        assertThat(cast.redirectHeaders("/?p=" + cast.hello.value(), Viewer.anonymous()))
                .containsExactly(
                        entry("Content-Type", List.of(HTML)),
                        entry("Cache-Control", List.of("public, max-age=60")),
                        entry("X-Redirect-By", List.of("QuarkWord")),
                        entry("Location", List.of(hello)));
        // a published post asked for as a preview without a valid pair is no preview once redirected (WordPress
        // clears the flag before it redirects): no preview headers, no no-cache set
        assertThat(cast.redirectHeaders("/?p=" + cast.hello.value() + "&preview=true", Viewer.anonymous()))
                .containsExactly(
                        entry("Content-Type", List.of(HTML)),
                        entry("Cache-Control", List.of("public, max-age=60")),
                        entry("X-Redirect-By", List.of("QuarkWord")),
                        entry("Location", List.of(hello)));
        // a redirect after the 404 decision keeps its no-cache set (FR15-page, G13)
        assertThat(cast.redirectHeaders("/2025/11/23/second-post/3/", Viewer.anonymous()))
                .containsExactly(
                        entry("Expires", List.of(EXPIRES)),
                        entry("Cache-Control", List.of(NO_CACHE)),
                        entry("Content-Type", List.of(HTML)),
                        entry("X-Redirect-By", List.of("QuarkWord")),
                        entry("Location", List.of("http://wp.test/2025/11/23/second-post/")));
        // an index.php/ prefix under a structure without it matches the page rule and is not found, then the redirect
        // drops it (G14: WordPress's 301 carries the no-cache set)
        assertThat(cast.redirectHeaders("/index.php/2025/11/20/hello-world/", Viewer.anonymous()))
                .containsExactly(
                        entry("Expires", List.of(EXPIRES)),
                        entry("Cache-Control", List.of(NO_CACHE)),
                        entry("Content-Type", List.of(HTML)),
                        entry("X-Redirect-By", List.of("QuarkWord")),
                        entry("Location", List.of(hello)));
    }
}
