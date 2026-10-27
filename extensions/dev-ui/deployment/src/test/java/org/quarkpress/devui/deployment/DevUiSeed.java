package org.quarkpress.devui.deployment;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.concurrent.Callable;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.multisite.NewSite;
import org.quarkpress.multisite.Sites;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.Posts;
import org.quarkpress.revisions.Revisions;
import org.quarkpress.security.SiteRoles;
import org.quarkpress.security.SystemIdentity;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.Terms;

/**
 * Content for the Dev UI's reads, written as the system each time the application starts (a dev-mode test starts a new
 * application with an empty database before each test method). Site 1: the posts {@code Hello} and {@code Second}
 * (published), {@code Draft}, {@code Later} (scheduled) and the page {@code About}; the terms {@code News}
 * (category), {@code alpha} and {@code beta} (tags); one revision of the draft. Then the site
 * {@code second.example.test} with the scheduled post {@code Elsewhere}, whose subscribers may also
 * {@code upload_files}.
 */
@ApplicationScoped
public class DevUiSeed {

    @Inject
    SiteScope siteScope;

    @Inject
    SystemIdentity system;

    @Inject
    Posts posts;

    @Inject
    Terms terms;

    @Inject
    Revisions revisions;

    @Inject
    Sites sites;

    @Inject
    SiteRoles siteRoles;

    void seed(@Observes StartupEvent start) throws Exception {
        SiteId main = SiteId.of(1);
        post(main, "post", "publish", "Hello", null);
        post(main, "post", "publish", "Second", null);
        PostId draft = post(main, "post", "draft", "Draft", null);
        post(main, "post", "future", "Later", Instant.parse("2090-01-01T00:00:00Z"));
        post(main, "page", "publish", "About", null);
        term(main, "category", "News");
        term(main, "post_tag", "alpha");
        term(main, "post_tag", "beta");
        asSystemOn(main, () -> revisions.save(draft));
        SiteId second = system.call(() -> sites.create(NewSite.of("second.example.test", "/", "Second")))
                .id();
        post(second, "post", "future", "Elsewhere", Instant.parse("2089-12-31T00:00:00Z"));
        asSystemOn(second, () -> siteRoles.setCapability(second, "subscriber", "upload_files", true));
    }

    private PostId post(SiteId site, String type, String status, String title, Instant dateGmt) throws Exception {
        PostChanges.Builder changes =
                PostChanges.builder().type(type).status(status).title(title).content(title + ".");
        if (dateGmt != null) {
            changes.dateGmt(dateGmt);
        }
        return asSystemOn(site, () -> posts.create(changes.build()).id());
    }

    private void term(SiteId site, String taxonomy, String name) throws Exception {
        asSystemOn(
                site,
                () -> terms.create(taxonomy, TermChanges.builder().name(name).build()));
    }

    private <T> T asSystemOn(SiteId site, Callable<T> work) throws Exception {
        return siteScope.call(site, () -> system.call(work));
    }
}
