package org.quarkword.front.deployment;

import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.Arc;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.core.TermId;
import org.quarkword.core.UserId;
import org.quarkword.front.FrontRequest;
import org.quarkword.front.FrontResolver;
import org.quarkword.front.Viewer;
import org.quarkword.front.runtime.AttachmentFiles;
import org.quarkword.front.runtime.CanonicalRedirects;
import org.quarkword.front.runtime.FrontHeaders;
import org.quarkword.front.runtime.OldSlugRedirects;
import org.quarkword.front.runtime.Redirect;
import org.quarkword.front.runtime.RequestUrl;

/**
 * The routing reference's cast on a new site whose {@code home} is {@code http://wp.test}, with the default structure:
 * alice (administrator, nicename {@code alice-<site>}) and bob (subscriber, {@code bob-smith-<site>}, no posts); categories
 * Uncategorized, News and Releases (a child of News), tag Java, genre Scifi; posts hello-world (2025-11-20, once
 * {@code hello}), second-post (2025-11-23, two content pages, once dated 2025-11-01, in News, Releases and Java),
 * java-21 and java-17 (in News), january (2025-01-05), the Cyrillic slug {@code %d0%ba%d0%be%d1%82}, draft draft-post, private secret (once {@code old-secret}), scheduled coming; pages
 * sample-page, about, team (under about); attachments photo (of second-post), logo (unattached) and draft-shot (of the
 * draft), each with a file; book dune (once {@code old-dune}). Requests go through the resolver and the redirect
 * deciders as the front end runs them, on the host {@code wp.test} unless a test names another.
 */
final class RedirectCast {

    static final String HOME = "http://wp.test";
    static final String DEFAULT = "/%year%/%monthnum%/%day%/%postname%/";

    final AgroalDataSource dataSource;
    final SiteId site;
    final UserId alice;
    final UserId bob;
    final TermId uncategorized;
    final TermId news;
    final TermId releases;
    final TermId java;
    final TermId scifi;
    final PostId hello;
    final PostId second;
    final PostId java21;
    final PostId java17;
    final PostId january;
    final PostId cyrillic;
    final PostId draft;
    final PostId secret;
    final PostId coming;
    final PostId sample;
    final PostId about;
    final PostId team;
    final PostId photo;
    final PostId logo;
    final PostId draftShot;
    final PostId dune;

    RedirectCast(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
        site = TestData.site(dataSource);
        // the site's own address is wp.test when no other site has it, so that its files are under the home host
        if (Jdbc.count(dataSource, "select count(*) from qw_site where domain = 'wp.test'") == 0) {
            Jdbc.update(dataSource, "update qw_site set domain = 'wp.test' where id = ?", site.value());
        }
        option("home", "\"" + HOME + "\"");
        option("siteurl", "\"" + HOME + "\"");
        option("permalink_structure", "\"" + DEFAULT + "\"");
        alice = user("alice", "alice", "administrator");
        bob = user("bob", "bob-smith", "subscriber");
        uncategorized = TestData.term(dataSource, site, "category", "Uncategorized", "uncategorized", null);
        news = TestData.term(dataSource, site, "category", "News", "news", null);
        releases = TestData.term(dataSource, site, "category", "Releases", "releases", news);
        java = TestData.term(dataSource, site, "post_tag", "Java", "java", null);
        scifi = TestData.term(dataSource, site, "genre", "Scifi", "scifi", null);
        hello = post("post", "publish", "hello-world", null, "2025-11-20T10:15:30Z");
        meta(hello, "{\"_wp_old_slug\":[\"hello\"]}");
        TestData.assign(dataSource, site, hello, uncategorized);
        second = post("post", "publish", "second-post", null, "2025-11-23T08:00:00Z");
        Jdbc.update(dataSource, "update qw_post set content = 'One<!--nextpage-->Two' where id = ?", second.value());
        meta(second, "{\"_wp_old_date\":[\"2025-11-01\"]}");
        TestData.assign(dataSource, site, second, news);
        TestData.assign(dataSource, site, second, releases);
        TestData.assign(dataSource, site, second, java);
        java21 = post("post", "publish", "java-21", null, "2025-10-20T08:00:00Z");
        java17 = post("post", "publish", "java-17", null, "2025-10-21T08:00:00Z");
        TestData.assign(dataSource, site, java21, news);
        TestData.assign(dataSource, site, java17, news);
        january = post("post", "publish", "january", null, "2025-01-05T08:00:00Z");
        // a slug WordPress keeps percent-encoded (lower-case hex), as its save sanitising leaves Cyrillic letters
        cyrillic = post("post", "publish", "%d0%ba%d0%be%d1%82", null, "2025-11-20T11:00:00Z");
        draft = TestData.post(
                dataSource, site, "post", "draft", "draft-post", alice, null, "2025-11-24T08:00:00Z", true);
        secret = post("post", "private", "secret", null, "2025-11-22T09:00:00Z");
        meta(secret, "{\"_wp_old_slug\":[\"old-secret\"]}");
        coming = post("post", "future", "coming", null, "2027-01-05T09:00:00Z");
        sample = post("page", "publish", "sample-page", null, "2025-11-19T10:00:00Z");
        about = post("page", "publish", "about", null, "2025-11-20T10:00:00Z");
        team = post("page", "publish", "team", about, "2025-11-20T10:00:00Z");
        photo = post("attachment", "inherit", "photo", second, "2025-11-23T08:30:00Z");
        logo = post("attachment", "inherit", "logo", null, "2025-11-23T08:31:00Z");
        draftShot = post("attachment", "inherit", "draft-shot", draft, "2025-11-24T08:30:00Z");
        for (PostId attachment : List.of(photo, logo, draftShot)) {
            file(attachment);
        }
        dune = post("book", "publish", "dune", null, "2025-11-23T08:00:00Z");
        meta(dune, "{\"_wp_old_slug\":[\"old-dune\"]}");
    }

    /** A member with the nicename {@code nicename-<site id>} (nicenames are unique across the installation). */
    private UserId user(String name, String nicename, String role) {
        UserId user = TestData.profile(dataSource, name, nicename + "-" + site.value());
        TestData.member(dataSource, TestData.MAIN, user, "[\"" + role + "\"]");
        TestData.member(dataSource, site, user, "[\"" + role + "\"]");
        return user;
    }

    PostId post(String type, String status, String slug, PostId parent, String date) {
        return TestData.post(dataSource, site, type, status, slug, alice, parent, date, false);
    }

    void meta(PostId post, String json) {
        Jdbc.update(dataSource, "update qw_post set meta = " + TestDb.json() + " where id = ?", json, post.value());
    }

    /** A media row: the attachment's file is {@code 2025/11/<slug>.jpg}. */
    void file(PostId attachment) {
        String slug = Jdbc.strings(dataSource, "select slug from qw_post where id = ?", attachment.value())
                .get(0);
        Jdbc.update(
                dataSource,
                "insert into qw_media (post_id, site_id, storage_key, mime_type, file_size, alt_text, processing,"
                        + " stored_bytes) values (?, ?, ?, 'image/jpeg', 1, '', 'done', 1)",
                attachment.value(),
                site.value(),
                "2025/11/" + slug + ".jpg");
    }

    void option(String name, String json) {
        TestData.siteOption(dataSource, site, name, json);
    }

    void structure(String structure) {
        option("permalink_structure", "\"" + structure + "\"");
    }

    /** The default options again: default structure, posts on the front, attachment pages off, home on http. */
    void reset() {
        structure(DEFAULT);
        option("home", "\"" + HOME + "\"");
        option("show_on_front", "\"posts\"");
        option("page_on_front", "0");
        option("page_for_posts", "0");
        option("wp_attachment_pages_enabled", "0");
        TestData.dropOption(dataSource, site, "posts_per_page");
    }

    /** The viewer {@code user} logged in. */
    static Viewer as(UserId user) {
        return FrontCast.as(user);
    }

    // ---- requests -------------------------------------------------------------------------------------------------

    /** The canonical redirect of an anonymous GET of {@code target} on {@code wp.test}. */
    Optional<Redirect> canonical(String target) throws Exception {
        return canonical("wp.test", target, Viewer.anonymous());
    }

    Optional<Redirect> canonical(String target, Viewer viewer) throws Exception {
        return canonical("wp.test", target, viewer);
    }

    Optional<Redirect> canonical(String host, String target, Viewer viewer) throws Exception {
        return canonical("http", host, target, viewer);
    }

    Optional<Redirect> canonical(String scheme, String host, String target, Viewer viewer) throws Exception {
        return canonical(new RequestUrl("GET", scheme, host, target), viewer);
    }

    Optional<Redirect> canonical(RequestUrl url, Viewer viewer) throws Exception {
        return onSite(viewer, () -> {
            FrontRequest request = resolve(url.target(), viewer);
            return Arc.container().instance(CanonicalRedirects.class).get().decide(request, url, viewer);
        });
    }

    /** The old slug redirect of an anonymous GET of {@code target}. */
    Optional<Redirect> oldSlug(String target) throws Exception {
        return oldSlug(target, Viewer.anonymous());
    }

    Optional<Redirect> oldSlug(String target, Viewer viewer) throws Exception {
        return oldSlug(target, viewer, viewer);
    }

    /**
     * The old slug redirect of a GET of {@code target} as {@code viewer} while the request's own identity is {@code
     * ambient}'s (a preview session's viewer differs from the request's identity).
     */
    Optional<Redirect> oldSlug(String target, Viewer viewer, Viewer ambient) throws Exception {
        return onSite(
                ambient,
                () -> Arc.container().instance(OldSlugRedirects.class).get().decide(resolve(target, viewer), viewer));
    }

    /** What the front end redirects an anonymous GET to: the old slug redirect, else the canonical one. */
    Optional<Redirect> redirect(String target) throws Exception {
        return redirect(target, Viewer.anonymous());
    }

    Optional<Redirect> redirect(String target, Viewer viewer) throws Exception {
        Optional<Redirect> old = oldSlug(target, viewer);
        return old.isPresent() ? old : canonical(target, viewer);
    }

    /** The resolved request of {@code target} as {@code viewer}. */
    FrontRequest request(String target, Viewer viewer) throws Exception {
        return onSite(viewer, () -> resolve(target, viewer));
    }

    /** The page headers of {@code target} as {@code viewer}. */
    Map<String, List<String>> headers(String target, Viewer viewer, boolean passwordProtected) throws Exception {
        return onSite(
                viewer,
                () -> Arc.container()
                        .instance(FrontHeaders.class)
                        .get()
                        .of(resolve(target, viewer), viewer, passwordProtected));
    }

    /** The redirect headers of {@code target} (its canonical redirect) as {@code viewer}. */
    Map<String, List<String>> redirectHeaders(String target, Viewer viewer) throws Exception {
        RequestUrl url = RequestUrl.get("wp.test", target);
        return onSite(viewer, () -> {
            FrontRequest request = resolve(target, viewer);
            Redirect redirect = Arc.container()
                    .instance(CanonicalRedirects.class)
                    .get()
                    .decide(request, url, viewer)
                    .orElseThrow();
            return Arc.container().instance(FrontHeaders.class).get().redirect(request, viewer, false, redirect);
        });
    }

    /** The file URL of {@code attachment}. */
    String fileOf(PostId attachment) throws Exception {
        return onSite(
                Viewer.anonymous(),
                () -> Arc.container()
                        .instance(AttachmentFiles.class)
                        .get()
                        .url(attachment)
                        .orElseThrow());
    }

    private <T> T onSite(Viewer viewer, Callable<T> work) throws Exception {
        return Identities.onSite(site, viewer.identity(), work);
    }

    private static FrontRequest resolve(String target, Viewer viewer) {
        int question = target.indexOf('?');
        String path = question < 0 ? target : target.substring(0, question);
        Map<String, List<String>> query = new LinkedHashMap<>();
        if (question >= 0) {
            for (String pair : target.substring(question + 1).split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int equals = pair.indexOf('=');
                String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
                String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                query.put(name, List.of(value));
            }
        }
        return Arc.container().instance(FrontResolver.class).get().resolve(path, query, viewer);
    }
}
