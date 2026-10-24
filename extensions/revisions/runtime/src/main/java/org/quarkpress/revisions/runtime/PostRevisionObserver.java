package org.quarkpress.revisions.runtime;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.quarkpress.posts.PostDeleting;
import org.quarkpress.posts.PostSaved;

/**
 * Saves revisions as the posts module saves posts, inside the transaction that saves the post (which holds the site's
 * post lock): after every save of a stored post (an update, a trash, an untrash, a publish), never after an insert,
 * the post's saved state is revisioned when WordPress would revision it. Runs before application observers, so they
 * see the revision. Before a post is deleted for good, its revisions are removed one by one and each is announced.
 */
@ApplicationScoped
public class PostRevisionObserver {

    @Inject
    RevisionWrites writes;

    void saved(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) PostSaved event) {
        if (!event.created()) {
            writes.saveRevision(event.site(), event.post());
        }
    }

    void deleting(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) PostDeleting event) {
        writes.deleteAllOf(event.site(), event.post().id());
    }
}
