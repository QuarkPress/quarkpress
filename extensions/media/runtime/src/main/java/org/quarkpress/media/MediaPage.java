package org.quarkpress.media;

import java.util.List;

/**
 * One page of a media library query: the attachments, the number of attachments the query matches without paging (0
 * when the page is empty, as WordPress reports it) and the number of pages (0 without paging), as {@code PostPage}
 * counts them.
 */
public record MediaPage(List<MediaSnapshot> items, long found, long maxPages) {
    public MediaPage {
        items = List.copyOf(items);
    }
}
