package org.quarkpress.it.posts;

import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostSnapshot;

/** A post as the test endpoints return it. */
public record PostView(
        long id,
        String type,
        String status,
        String slug,
        String title,
        String content,
        Long author,
        Long parent,
        boolean sticky,
        int version) {

    static PostView of(PostSnapshot post) {
        return new PostView(
                post.id().value(),
                post.type(),
                post.status(),
                post.slug(),
                post.title(),
                post.content(),
                post.author().map(UserId::value).orElse(null),
                post.parent().map(PostId::value).orElse(null),
                post.sticky(),
                post.version());
    }
}
