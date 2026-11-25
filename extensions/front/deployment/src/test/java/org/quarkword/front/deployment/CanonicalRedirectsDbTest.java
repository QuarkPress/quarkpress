package org.quarkword.front.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkword.front.deployment.RedirectCast.as;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.front.Viewer;
import org.quarkword.front.runtime.Redirect;
import org.quarkword.front.runtime.RequestUrl;

/**
 * WordPress's canonical redirect over the routing reference's cast (default structure, home {@code http://wp.test}).
 * Every expectation is WordPress 7.1.3's recorded answer (front-facts.json, items CANON, G7 to G19, G4, G9, PAGED,
 * FRONT-PAGE: the same requests on the parity site {@code /gcan/}); where the reference's prose or the task's brief
 * said otherwise, the test follows WordPress.
 */
class CanonicalRedirectsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = FrontApp.with(
            CanonicalRedirectsDbTest.class, RedirectCast.class, FrontCast.class, BookType.class, GenreTaxonomy.class);

    static final String W = "http://wp.test";
    static final String HELLO = W + "/2025/11/20/hello-world/";
    static final String SECOND = W + "/2025/11/23/second-post/";

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

    private static Optional<Redirect> get(String target) throws Exception {
        return cast.canonical(target);
    }

    @Test
    void plainIdsBecomePermalinks() throws Exception {
        long hello = cast.hello.value();
        assertThat(get("/?p=" + hello)).isEqualTo(to(HELLO));
        assertThat(get("/?p=" + hello + "&utm_source=x")).isEqualTo(to(HELLO + "?utm_source=x"));
        assertThat(get("/?p=" + hello + "&preview=true")).isEqualTo(to(HELLO));
        assertThat(get("/?page_id=" + cast.team.value())).isEqualTo(to(W + "/about/team/"));
        assertThat(get("/?page_id=" + cast.sample.value())).isEqualTo(to(W + "/sample-page/"));
        assertThat(get("/?post_type=book&p=" + cast.dune.value())).isEqualTo(to(W + "/books/dune/"));
        assertThat(get("/?name=hello-world")).isEqualTo(to(HELLO));
        assertThat(get("/?name=hello-world&utm=1")).isEqualTo(to(HELLO + "?utm=1"));
        // a name that does not match makes the request a 404: the id's permalink, the name dropped
        assertThat(get("/?p=" + hello + "&name=foo")).isEqualTo(to(HELLO));
        assertThat(get("/?pagename=about/team")).isEmpty();
        // only the consumed variable is dropped (FR6 facts: ?post_type=page&page_id=...)
        assertThat(get("/?post_type=page&page_id=" + cast.team.value()))
                .isEqualTo(to(W + "/about/team/?post_type=page"));
    }

    @Test
    void hiddenPostsAreNotRevealed() throws Exception {
        // private: a 404 for a visitor (its plain link is the request), the permalink for a reader
        assertThat(get("/?p=" + cast.secret.value())).isEmpty();
        assertThat(cast.canonical("/?p=" + cast.secret.value(), as(cast.alice)))
                .isEqualTo(to(W + "/2025/11/22/secret/"));
        // a draft shown to its editor is a preview: no canonical redirect
        assertThat(cast.canonical("/?p=" + cast.draft.value(), as(cast.alice))).isEmpty();
        assertThat(cast.canonical("/?p=" + cast.draft.value() + "&preview=true", as(cast.alice)))
                .isEmpty();
        assertThat(get("/?p=" + cast.draft.value())).isEmpty();
        assertThat(get("/?p=" + cast.coming.value())).isEmpty();
    }

    @Test
    void theStaticFrontPage() throws Exception {
        cast.option("show_on_front", "\"page\"");
        cast.option("page_on_front", Long.toString(cast.sample.value()));
        assertThat(get("/sample-page/")).isEqualTo(to(W + "/"));
        assertThat(get("/?page_id=" + cast.sample.value())).isEqualTo(to(W + "/"));
        assertThat(get("/?page_id=" + cast.sample.value() + "&page=2")).isEqualTo(to(W + "/"));
        assertThat(get("/")).isEmpty();
        assertThat(get("/page/2/")).isEmpty();
        cast.option("page_for_posts", Long.toString(cast.about.value()));
        assertThat(get("/?page_id=" + cast.about.value())).isEqualTo(to(W + "/about/"));
        assertThat(get("/about/")).isEmpty();
    }

    @Test
    void datesAndAuthors() throws Exception {
        assertThat(get("/?m=202511")).isEqualTo(to(W + "/2025/11/"));
        assertThat(get("/?m=20251123")).isEqualTo(to(W + "/2025/11/23/"));
        assertThat(get("/?year=2025&monthnum=11")).isEqualTo(to(W + "/2025/11/"));
        assertThat(get("/?year=2025")).isEqualTo(to(W + "/2025/"));
        assertThat(get("/?monthnum=11")).isEmpty();
        String alice = "alice-" + cast.site.value();
        assertThat(get("/?author=" + cast.alice.value())).isEqualTo(to(W + "/author/" + alice + "/"));
        // bob has no published post: his archive stays where it is
        assertThat(get("/?author=" + cast.bob.value())).isEmpty();
        assertThat(get("/?author=alice")).isEmpty();
        assertThat(get("/author/" + alice + "/page/1/")).isEqualTo(to(W + "/author/" + alice + "/"));
    }

    @Test
    void termsFromTheQueryStringAreRedirected() throws Exception {
        // front-facts G8: WordPress redirects a term archive whose variables all came from the query string
        long news = cast.news.value();
        assertThat(get("/?cat=" + news)).isEqualTo(to(W + "/category/news/"));
        assertThat(get("/?cat=" + news + "&utm_source=x")).isEqualTo(to(W + "/category/news/?utm_source=x"));
        assertThat(get("/?tag=java")).isEqualTo(to(W + "/tag/java/"));
        assertThat(get("/?category_name=news")).isEqualTo(to(W + "/category/news/"));
        assertThat(get("/?category_name=news/releases")).isEqualTo(to(W + "/category/news/releases/"));
        assertThat(get("/?genre=scifi")).isEqualTo(to(W + "/genre/scifi/"));
        assertThat(get("/category/news/?cat=" + news)).isEmpty();
        assertThat(get("/category/news/?utm_source=x")).isEmpty();
        assertThat(get("/?cat=" + news + "," + cast.releases.value())).isEmpty();
    }

    @Test
    void whatIsNotRedirected() throws Exception {
        // front-facts G7, G12, the search
        assertThat(get("/?book=dune")).isEmpty();
        assertThat(get("/?post_type=book")).isEmpty();
        assertThat(get("/?s=java")).isEmpty();
        assertThat(get("/2025/11/20/hello-world/")).isEmpty();
        assertThat(get("/2025/11/20/hello-world/?replytocom=5")).isEmpty();
        assertThat(get("/")).isEmpty();
    }

    @Test
    void listPaging() throws Exception {
        assertThat(get("/page/1/")).isEqualTo(to(W + "/"));
        assertThat(get("/?paged=1")).isEqualTo(to(W + "/"));
        assertThat(get("/category/news/page/1/")).isEqualTo(to(W + "/category/news/"));
        assertThat(get("/books/page/1/")).isEqualTo(to(W + "/books/"));
        // a single post drops the list page without a suffix (G10)
        assertThat(get("/2025/11/20/hello-world/page/2/")).isEqualTo(to(HELLO));
        // the list page is normalised only when that page exists: a page past the end is a 404, left alone
        assertThat(get("/?paged=2")).isEmpty();
        cast.option("posts_per_page", "1");
        assertThat(get("/?paged=2")).isEqualTo(to(W + "/page/2/"));
        assertThat(get("/?paged=2&utm=1")).isEqualTo(to(W + "/page/2/?utm=1"));
        assertThat(get("/category/news/?paged=3")).isEqualTo(to(W + "/category/news/page/3/"));
        assertThat(get("/category/news/page/2/?paged=3")).isEqualTo(to(W + "/category/news/page/3/"));
        // front-facts PAGED: a page path "page/2" with a list page, not found: no redirect
        assertThat(get("/page/2/page/3/")).isEmpty();
        assertThat(get("/page/2")).isEqualTo(to(W + "/page/2/"));
        assertThat(get("/2025/?paged=2")).isEqualTo(to(W + "/2025/page/2/"));
        // the list page of a single post asked for by p replaces the target with the path: the home
        assertThat(get("/?p=" + cast.hello.value() + "&paged=2")).isEqualTo(to(W + "/"));
    }

    @Test
    void contentPages() throws Exception {
        assertThat(get("/2025/11/23/second-post/?page=2")).isEqualTo(to(SECOND + "2/"));
        assertThat(get("/2025/11/23/second-post/1/")).isEqualTo(to(SECOND));
        assertThat(get("/2025/11/23/second-post/2/")).isEmpty();
        assertThat(get("/2025/11/23/second-post/3/")).isEqualTo(to(SECOND));
        // page 0 is no page (G13)
        assertThat(get("/2025/11/23/second-post/0/")).isEmpty();
        assertThat(get("/?p=" + cast.hello.value() + "&page=2")).isEqualTo(to(HELLO));
        assertThat(get("/?p=" + cast.second.value() + "&page=2")).isEqualTo(to(SECOND + "2/"));
        assertThat(get("/2025/11/20/hello-world/5/")).isEqualTo(to(HELLO));
        assertThat(get("/2025/11/20/hello-world/15/")).isEqualTo(to(HELLO));
    }

    @Test
    void impossibleDates() throws Exception {
        assertThat(get("/2025/02/30/")).isEqualTo(to(W + "/2025/02/"));
        assertThat(get("/2025/13/")).isEqualTo(to(W + "/2025/"));
        // a found day with one-digit parts is served where it is (G19)
        assertThat(get("/2025/1/5/")).isEmpty();
        assertThat(get("/2025/01/05/")).isEmpty();
    }

    @Test
    void theNotFoundGuess() throws Exception {
        assertThat(get("/abou/")).isEqualTo(to(W + "/about/"));
        assertThat(get("/About/")).isEmpty();
        assertThat(get("/About/Team/")).isEmpty();
        assertThat(get("/about/teem/")).isEmpty();
        assertThat(get("/2025/11/21/hello-world/")).isEmpty();
        // front-facts CANON: "sec" is the start of the published second-post (the private secret is never guessed)
        assertThat(get("/sec/")).isEqualTo(to(SECOND));
        assertThat(get("/dra/")).isEmpty();
        // front-facts G4: the first slug in the database's slug order, java-17 before java-21
        assertThat(get("/jav/")).isEqualTo(to(W + "/2025/10/21/java-17/"));
        assertThat(get("/2025/11/20/HELLO-WO/")).isEqualTo(to(HELLO));
    }

    @Test
    void urlNormalisations() throws Exception {
        assertThat(get("/2025/11/20/hello-world")).isEqualTo(to(HELLO));
        assertThat(get("/category/news")).isEqualTo(to(W + "/category/news/"));
        assertThat(get("/about/team")).isEqualTo(to(W + "/about/team/"));
        assertThat(get("/index.php")).isEqualTo(to(W + "/"));
        assertThat(get("/index.php/2025/11/20/hello-world/")).isEqualTo(to(HELLO));
        assertThat(get("/2025/11/20/hello-world/.")).isEqualTo(to(HELLO));
        assertThat(get("/2025/11/20/hello-world/%20")).isEqualTo(to(HELLO));
        assertThat(get("/2025/11/20/hello-world/%C2%A0")).isEqualTo(to(HELLO));
        assertThat(get("/?p=" + cast.hello.value() + ".")).isEqualTo(to(HELLO));
        assertThat(get("/?p=" + cast.hello.value() + "%2E")).isEqualTo(to(HELLO));
        // front-facts CANON: an empty p is removed, which changes the query: a redirect to the home
        assertThat(get("/?p=")).isEqualTo(to(W + "/"));
        assertThat(get("/?p=&utm_source=x")).isEqualTo(to(W + "/?utm_source=x"));
        assertThat(get("//2025//11//20//hello-world//")).isEqualTo(to(HELLO));
        assertThat(get("/2025/11/20/hello-world?replytocom=5")).isEqualTo(to(HELLO + "?replytocom=5"));
        cast.structure("/%postname%");
        assertThat(get("/hello-world/")).isEqualTo(to(W + "/hello-world"));
        assertThat(get("/hello-world")).isEmpty();
    }

    @Test
    void encodedPathCase() throws Exception {
        // front-facts G11: the hex case of the request is left as it is, a permalink uses the stored lower case
        String lower = "/2025/11/20/%d0%ba%d0%be%d1%82/";
        String upper = "/2025/11/20/%D0%BA%D0%BE%D1%82/";
        assertThat(get(lower)).isEmpty();
        assertThat(get(upper)).isEmpty();
        assertThat(get(upper.substring(0, upper.length() - 1))).isEqualTo(to(W + upper));
        assertThat(get(lower.substring(0, lower.length() - 1))).isEqualTo(to(W + lower));
        assertThat(get("/?p=" + cast.cyrillic.value())).isEqualTo(to(W + lower));
    }

    @Test
    void survivingArguments() throws Exception {
        long hello = cast.hello.value();
        assertThat(get("/?p=" + hello + "&utm_source=nl&x=a%20b")).isEqualTo(to(HELLO + "?utm_source=nl&x=a%20b"));
        assertThat(get("/?p=" + hello + "&x=a+b&y=&z")).isEqualTo(to(HELLO + "?x=a%20b&y&z"));
        assertThat(get("/?p=" + hello + "&a%5B%5D=1&a%5B%5D=2&b.c=3"))
                .isEqualTo(to(HELLO + "?a%5B0%5D=1&a%5B1%5D=2&b_c=3"));
    }

    @Test
    void hostsAndSchemes() throws Exception {
        assertThat(cast.canonical("www.wp.test", "/2025/11/20/hello-world/", Viewer.anonymous()))
                .isEqualTo(to(HELLO));
        assertThat(cast.canonical("WP.TEST", "/2025/11/20/hello-world/", Viewer.anonymous()))
                .isEmpty();
        // WordPress keeps an alias host: the alias redirect is the site gate's (FrontSiteGateDbTest)
        assertThat(cast.canonical("alias.test", "/2025/11/20/hello-world/", Viewer.anonymous()))
                .isEmpty();
        // on an alias, the permalink (on the home host) is redirected again from the alias' own address: the chain
        // check cancels it
        assertThat(cast.canonical("alias.test", "/?p=" + cast.hello.value(), Viewer.anonymous()))
                .isEmpty();
        // a port home does not have is dropped
        assertThat(cast.canonical("wp.test:8080", "/2025/11/20/hello-world/", Viewer.anonymous()))
                .isEqualTo(to(HELLO));
        cast.option("home", "\"https://wp.test\"");
        assertThat(get("/?p=" + cast.hello.value())).isEqualTo(to("https://wp.test/2025/11/20/hello-world/"));
        // only a replaced URL takes home's scheme: the request's own scheme is kept otherwise
        assertThat(get("/2025/11/20/hello-world/")).isEmpty();
        assertThat(get("/2025/11/20/hello-world")).isEqualTo(to(HELLO));
    }

    @Test
    void underTheCategoryStructure() throws Exception {
        cast.structure("/%category%/%postname%/");
        assertThat(get("/news/second-post/")).isEmpty();
        assertThat(get("/news/releases/second-post/")).isEmpty();
        assertThat(get("/releases/second-post/")).isEqualTo(to(W + "/news/second-post/"));
        assertThat(get("/uncategorized/second-post/")).isEqualTo(to(W + "/news/second-post/"));
        assertThat(get("/sports/second-post/")).isEqualTo(to(W + "/news/second-post/"));
        assertThat(get("/?p=" + cast.second.value())).isEqualTo(to(W + "/news/second-post/"));
    }

    @Test
    void plainModeRedirectsNothingByItsRules() throws Exception {
        cast.structure("");
        assertThat(get("/?p=" + cast.hello.value())).isEmpty();
        assertThat(get("/anything/?p=" + cast.hello.value())).isEmpty();
        assertThat(get("/?cat=" + cast.news.value())).isEmpty();
    }

    @Test
    void onlyReads() throws Exception {
        String target = "/?p=" + cast.hello.value();
        assertThat(cast.canonical(new RequestUrl("HEAD", "http", "wp.test", target), Viewer.anonymous()))
                .isEqualTo(to(HELLO));
        assertThat(cast.canonical(new RequestUrl("POST", "http", "wp.test", target), Viewer.anonymous()))
                .isEmpty();
    }
}
