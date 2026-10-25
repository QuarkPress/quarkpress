package org.quarkpress.it.posts;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.quarkpress.posts.PostPublished;

/** The posts this node published since it started, in order: one entry per committed {@link PostPublished}. */
@ApplicationScoped
public class PublishedPosts {

    private final List<Long> ids = new CopyOnWriteArrayList<>();

    void published(@Observes(during = TransactionPhase.AFTER_SUCCESS) PostPublished event) {
        ids.add(event.post().id().value());
    }

    public List<Long> ids() {
        return List.copyOf(ids);
    }
}
