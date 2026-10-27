package org.quarkpress.it.devui.posts;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.Posts;
import org.quarkpress.security.SystemIdentity;

/**
 * Content for the Dev UI's counts, written as the system each time the application starts (a dev-mode test starts a
 * new application with an empty database before each test method): two published posts and one draft page on site 1.
 */
@ApplicationScoped
public class PostsSeed {

    @Inject
    SiteScope siteScope;

    @Inject
    SystemIdentity system;

    @Inject
    Posts posts;

    void seed(@Observes StartupEvent start) throws Exception {
        post("post", "publish", "Hello");
        post("post", "publish", "Second");
        post("page", "draft", "About");
    }

    private void post(String type, String status, String title) throws Exception {
        PostChanges changes = PostChanges.builder()
                .type(type)
                .status(status)
                .title(title)
                .content(title + ".")
                .build();
        siteScope.call(SiteId.of(1), () -> system.call(() -> posts.create(changes)));
    }
}
