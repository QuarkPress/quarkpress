package org.quarkpress.posts;

import java.util.List;

/**
 * One page of query results: the posts, the number of posts the query matches without paging (0 when the page is
 * empty, as WordPress reports it) and the number of pages (0 without paging). For a query by id or slug, {@code found}
 * counts the posts the caller is shown, so it is 0 when the only post found is hidden from the caller; WordPress counts
 * that post (1) before it hides it.
 */
public record PostPage(List<PostSnapshot> posts, long found, long maxPages) {
    public PostPage {
        posts = List.copyOf(posts);
    }
}
