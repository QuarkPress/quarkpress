package org.quarkword.front.runtime;

import java.util.Objects;

/**
 * The content pages of a post, as WordPress splits its content when it sets the post up for display: around each
 * {@code <!--nextpage-->} marker one line break on each side is removed, the block delimiters {@code <!-- wp:nextpage
 * -->} and {@code <!-- /wp:nextpage -->} are removed, a marker at the very start is dropped, and the content is split
 * on the markers. Content without a marker is one page.
 */
public final class ContentPages {

    /** The marker that separates content pages. */
    public static final String MARKER = "<!--nextpage-->";

    private ContentPages() {}

    /** The number of content pages of {@code content}. */
    public static int count(String content) {
        Objects.requireNonNull(content, "content");
        if (!content.contains(MARKER)) {
            return 1;
        }
        String text = content.replace("\n" + MARKER, MARKER)
                .replace(MARKER + "\n", MARKER)
                .replace("<!-- wp:nextpage -->", "")
                .replace("<!-- /wp:nextpage -->", "");
        if (text.startsWith(MARKER)) {
            text = text.substring(MARKER.length());
        }
        int pages = 1;
        int from = 0;
        while (true) {
            int at = text.indexOf(MARKER, from);
            if (at < 0) {
                return pages;
            }
            pages++;
            from = at + MARKER.length();
        }
    }

    /** The number of markers in {@code content}, as WordPress's 404 decision counts them (no other processing). */
    static int markers(String content) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = content.indexOf(MARKER, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + MARKER.length();
        }
    }
}
