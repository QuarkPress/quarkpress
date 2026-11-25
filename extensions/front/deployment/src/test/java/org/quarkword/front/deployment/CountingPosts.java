package org.quarkword.front.deployment;

import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.quarkword.core.PostId;
import org.quarkword.posts.PostChanges;
import org.quarkword.posts.PostPage;
import org.quarkword.posts.PostQuery;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.Posts;

/** Counts the post queries of the application it is part of; everything else is passed on. */
@Decorator
@Priority(10)
public class CountingPosts implements Posts {

    /** The number of {@link #query} calls since the last reset. */
    static final AtomicInteger QUERIES = new AtomicInteger();

    @Inject
    @Delegate
    Posts delegate;

    @Override
    public PostPage query(PostQuery query) {
        QUERIES.incrementAndGet();
        return delegate.query(query);
    }

    @Override
    public PostSnapshot create(PostChanges changes) {
        return delegate.create(changes);
    }

    @Override
    public PostSnapshot update(PostId post, PostChanges changes) {
        return delegate.update(post, changes);
    }

    @Override
    public boolean publish(PostId post) {
        return delegate.publish(post);
    }

    @Override
    public boolean publishIfDue(PostId post) {
        return delegate.publishIfDue(post);
    }

    @Override
    public Optional<PostSnapshot> trash(PostId post) {
        return delegate.trash(post);
    }

    @Override
    public Optional<PostSnapshot> delete(PostId post, boolean force) {
        return delegate.delete(post, force);
    }

    @Override
    public Optional<PostSnapshot> untrash(PostId post) {
        return delegate.untrash(post);
    }

    @Override
    public Optional<PostSnapshot> find(PostId post) {
        return delegate.find(post);
    }

    @Override
    public String uniqueSlug(String type, String slug, Optional<PostId> post, Optional<PostId> parent, String status) {
        return delegate.uniqueSlug(type, slug, post, parent, status);
    }

    @Override
    public List<PostId> ancestors(PostId post) {
        return delegate.ancestors(post);
    }

    @Override
    public String uri(PostId post) {
        return delegate.uri(post);
    }

    @Override
    public boolean stick(PostId post) {
        return delegate.stick(post);
    }

    @Override
    public boolean unstick(PostId post) {
        return delegate.unstick(post);
    }

    @Override
    public List<PostId> stickyPosts() {
        return delegate.stickyPosts();
    }
}
