package org.quarkpress.posts.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.OptionTransactions;
import org.quarkpress.posts.PostCreated;
import org.quarkpress.posts.PostPublished;
import org.quarkpress.posts.PostSaved;
import org.quarkpress.posts.PostScheduled;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostStatusChanged;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.PostUpdated;
import org.quarkpress.posts.StickyChanged;

/**
 * Fires the events of a save in WordPress's order: the status transition, published or scheduled, updated (updates
 * that announce it), saved, then created (inserts). Published and scheduled are fired only for registered types. An
 * attachment's save fires none of these events (WordPress announces attachments with events of their own), but
 * publishing one fires them as for any other type. Synchronous observers run inside the transaction; one that throws
 * marks it for rollback whatever the exception type, so a caller that catches the exception cannot commit the save.
 * Asynchronous observers run after commit.
 */
@ApplicationScoped
public class PostEvents {

    @Inject
    OptionTransactions transactions;

    @Inject
    PostTypes types;

    @Inject
    Event<PostStatusChanged> statusChanged;

    @Inject
    Event<PostPublished> published;

    @Inject
    Event<PostScheduled> scheduled;

    @Inject
    Event<PostUpdated> updated;

    @Inject
    Event<PostSaved> saved;

    @Inject
    Event<PostCreated> created;

    @Inject
    Event<StickyChanged> stickyChanged;

    public void saved(
            SiteId site,
            Instant at,
            String from,
            Optional<PostSnapshot> before,
            PostSnapshot after,
            boolean announceUpdate) {
        if (after.type().equals("attachment")) {
            return;
        }
        fire(site, at, from, before, after, announceUpdate);
    }

    /**
     * The events of WordPress's {@code wp_publish_post}: the status transition, published (registered types) and
     * saved, for every type including attachments; the update is not announced.
     */
    public void published(SiteId site, Instant at, PostSnapshot before, PostSnapshot after) {
        fire(site, at, before.status(), Optional.of(before), after, false);
    }

    private void fire(
            SiteId site,
            Instant at,
            String from,
            Optional<PostSnapshot> before,
            PostSnapshot after,
            boolean announceUpdate) {
        transactions.publish(statusChanged, new PostStatusChanged(site, at, after, from, after.status()));
        boolean registered = types.registered(after.type());
        if (registered && after.status().equals("publish")) {
            transactions.publish(published, new PostPublished(site, at, after));
        } else if (registered && after.status().equals("future")) {
            transactions.publish(
                    scheduled,
                    new PostScheduled(site, at, after, after.publishedAt().orElse(at)));
        }
        if (before.isPresent() && announceUpdate) {
            transactions.publish(updated, new PostUpdated(site, at, before.get(), after));
        }
        transactions.publish(saved, new PostSaved(site, at, after, before.isEmpty()));
        if (before.isEmpty()) {
            transactions.publish(created, new PostCreated(site, at, after));
        }
    }

    /** A post was stuck or unstuck. */
    public void sticky(SiteId site, Instant at, PostId post, boolean sticky) {
        transactions.publish(stickyChanged, new StickyChanged(site, at, post, sticky));
    }
}
