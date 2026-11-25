package org.quarkword.front.deployment;

import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.Arc;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.core.TermId;
import org.quarkword.core.UserId;
import org.quarkword.front.FrontRequest;
import org.quarkword.front.FrontResolver;
import org.quarkword.front.Viewer;
import org.quarkword.posts.PostSnapshot;

/**
 * The query reference's cast on a new site, written past the services: users alice (administrator), bob (editor),
 * carol (author) and erin (subscriber); categories Uncategorized, News and Europe (a child of News); tags Cars, Paint
 * and Food; the seven published posts with their authors, terms, password and stickiness; draft 4, scheduled 5 and
 * private 6; pages 20 (about), 23 (team, under about), 21 (blog), 22 (home), 24 (privacy-policy) and draft page 25;
 * attachments 30 (photo, of post 3), 31 (logo, unattached) and 32 (draft-shot, of draft 4, uploaded by carol). The
 * reference's numbers are the fields' names; the stored ids differ. Plain permalinks; dates are UTC.
 */
final class FrontCast {

    final AgroalDataSource dataSource;
    final SiteId site;
    final UserId alice;
    final UserId bob;
    final UserId carol;
    final UserId erin;
    final TermId uncategorized;
    final TermId news;
    final TermId europe;
    final TermId cars;
    final TermId paint;
    final TermId food;
    final PostId p10;
    final PostId p9;
    final PostId p8;
    final PostId p3;
    final PostId p2;
    final PostId p1;
    final PostId p7;
    final PostId p4;
    final PostId p5;
    final PostId p6;
    final PostId about20;
    final PostId team23;
    final PostId blog21;
    final PostId home22;
    final PostId privacy24;
    final PostId hidden25;
    final PostId photo30;
    final PostId logo31;
    final PostId draftShot32;

    static final String LONG_STORY = "Intro<!--more-->Rest of one\n<!--nextpage-->\nTwo<!--nextpage-->Three";

    FrontCast(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
        site = TestData.site(dataSource);
        TestData.textOption(dataSource, site, "permalink_structure", "");
        alice = user("alice", "administrator");
        bob = user("bob", "editor");
        carol = user("carol", "author");
        erin = user("erin", "subscriber");
        uncategorized = TestData.term(dataSource, site, "category", "Uncategorized", "uncategorized", null);
        news = TestData.term(dataSource, site, "category", "News", "news", null);
        europe = TestData.term(dataSource, site, "category", "Europe", "europe", news);
        cars = TestData.term(dataSource, site, "post_tag", "Cars", "cars", null);
        paint = TestData.term(dataSource, site, "post_tag", "Paint", "paint", null);
        food = TestData.term(dataSource, site, "post_tag", "Food", "food", null);
        p10 = post("post", "publish", "long-story", "Long story", alice, null, "2026-07-10T08:00:00Z");
        content(p10, LONG_STORY);
        assign(p10, uncategorized);
        p9 = post("post", "publish", "secret-recipe", "Secret recipe", carol, null, "2026-06-01T08:00:00Z");
        Jdbc.update(dataSource, "update qw_post set password = 's3' where id = ?", p9.value());
        assign(p9, food);
        p8 = post("post", "publish", "car-painted-red", "Car painted red", bob, null, "2026-05-20T08:00:00Z");
        assign(p8, europe, cars, paint);
        p3 = post("post", "publish", "red-car-for-sale", "Red car for sale", carol, null, "2026-04-02T08:00:00Z");
        assign(p3, news, cars);
        p2 = post("post", "publish", "spring-notes", "Spring notes", bob, null, "2026-03-14T08:00:00Z");
        assign(p2, news);
        p1 = post("post", "publish", "hello-world", "Hello world!", alice, null, "2026-01-05T08:00:00Z");
        assign(p1, uncategorized);
        p7 = post("post", "publish", "welcome", "Welcome", alice, null, "2025-12-01T08:00:00Z");
        assign(p7, uncategorized);
        sticky(p7);
        p4 = post("post", "draft", "travel-diary", "Travel diary", carol, null, "2026-02-01T08:00:00Z");
        p5 = post("post", "future", "launch-day", "Launch day", bob, null, "2027-01-01T08:00:00Z");
        p6 = post("post", "private", "board-minutes", "Board minutes", alice, null, "2026-04-15T08:00:00Z");
        assign(p6, news);
        about20 = post("page", "publish", "about", "About", alice, null, "2026-01-02T08:00:00Z");
        team23 = post("page", "publish", "team", "Team", alice, about20, "2026-01-02T08:01:00Z");
        blog21 = post("page", "publish", "blog", "Blog", alice, null, "2026-01-02T08:02:00Z");
        home22 = post("page", "publish", "home", "Home", alice, null, "2026-01-02T08:03:00Z");
        privacy24 = post("page", "publish", "privacy-policy", "Privacy Policy", alice, null, "2026-01-02T08:04:00Z");
        hidden25 = post("page", "draft", "hidden", "Hidden", alice, null, "2026-01-02T08:05:00Z");
        photo30 = post("attachment", "inherit", "photo", "Photo", carol, p3, "2026-04-02T08:30:00Z");
        logo31 = post("attachment", "inherit", "logo", "Logo", alice, null, "2026-01-02T09:00:00Z");
        draftShot32 = post("attachment", "inherit", "draft-shot", "Draft shot", carol, p4, "2026-02-01T08:30:00Z");
        TestData.siteOption(dataSource, site, "wp_page_for_privacy_policy", Long.toString(privacy24.value()));
    }

    private UserId user(String name, String role) {
        UserId user = TestData.profile(dataSource, name, name + "-" + site.value());
        // a single-site install reads capabilities from the main site's memberships
        TestData.member(dataSource, TestData.MAIN, user, "[\"" + role + "\"]");
        TestData.member(dataSource, site, user, "[\"" + role + "\"]");
        return user;
    }

    private PostId post(
            String type, String status, String slug, String title, UserId author, PostId parent, String date) {
        PostId post = TestData.post(dataSource, site, type, status, slug, author, parent, date, false);
        Jdbc.update(dataSource, "update qw_post set title = ? where id = ?", title, post.value());
        return post;
    }

    private void content(PostId post, String content) {
        Jdbc.update(dataSource, "update qw_post set content = ? where id = ?", content, post.value());
    }

    private void assign(PostId post, TermId... terms) {
        for (TermId term : terms) {
            TestData.assign(dataSource, site, post, term);
        }
    }

    void sticky(PostId post) {
        Jdbc.update(dataSource, "update qw_post set sticky = true where id = ?", post.value());
    }

    void unstick(PostId post) {
        Jdbc.update(dataSource, "update qw_post set sticky = false where id = ?", post.value());
    }

    void option(String name, String json) {
        TestData.siteOption(dataSource, site, name, json);
    }

    void dropOption(String name) {
        TestData.dropOption(dataSource, site, name);
    }

    /** The viewer {@code user} logged in. */
    static Viewer as(UserId user) {
        return new Viewer(Optional.of(user), Identities.user(user));
    }

    /** The request for {@code path} with {@code query} (name to value, each once) as {@code viewer}. */
    FrontRequest resolve(String path, Map<String, String> query, Viewer viewer) throws Exception {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        query.forEach((name, value) -> parameters.put(name, List.of(value)));
        return Identities.onSite(
                site,
                viewer.identity(),
                () -> Arc.container().instance(FrontResolver.class).get().resolve(path, parameters, viewer));
    }

    /** The request for the query string {@code query} (PHP-decoded) at the site root, anonymously. */
    FrontRequest get(String query) throws Exception {
        return get(query, Viewer.anonymous());
    }

    /** The request for the query string {@code query} (PHP-decoded) at the site root as {@code viewer}. */
    FrontRequest get(String query, Viewer viewer) throws Exception {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (!query.isEmpty()) {
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                String name = equals < 0 ? pair : pair.substring(0, equals);
                String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                parameters.put(name, value);
            }
        }
        return resolve("/", parameters, viewer);
    }

    /** The ids of the request's posts, in order. */
    static List<PostId> ids(FrontRequest request) {
        return request.posts().posts().stream().map(PostSnapshot::id).toList();
    }
}
