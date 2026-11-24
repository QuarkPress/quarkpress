package org.quarkword.front;

import java.util.Optional;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.security.UserProfile;
import org.quarkword.taxonomy.TermSnapshot;

/**
 * The front-end links of the current site, built from its permalink structure as WordPress's link template builds
 * them: pretty links under {@code home} when a structure is set, {@code home/?query} links in plain mode, and plain
 * links for the posts a visitor may not see. Every link is absolute, with the request's scheme rules of
 * {@link FrontUrls}; a pretty link ends with a slash exactly when the structure does.
 */
public interface Permalinks {

    /**
     * {@code get_permalink}: a post's link by its type (the structure for posts and other built-in types, the page's
     * path, the attachment's parent link, a custom type's rewrite slug), plain when its status asks for a plain link
     * for the current visitor.
     */
    String post(PostSnapshot post);

    /**
     * The editor's permalink template ({@code get_sample_permalink}): the pretty link with {@code %postname%} or
     * {@code %pagename%} left literal, also for drafts, pending and scheduled posts; plain in plain mode and when
     * the type is unknown or a private post is not readable.
     */
    String postSample(PostSnapshot post);

    /** {@code get_term_link}: the term's link in its taxonomy's structure, ancestors first when it is hierarchical. */
    String term(String taxonomy, TermSnapshot term);

    /** {@code get_author_posts_url}: the author's archive by nicename. */
    String author(UserProfile author);

    /** {@code get_year_link}. */
    String year(int year);

    /** {@code get_month_link}: the month with two digits. */
    String month(int year, int month);

    /** {@code get_day_link}: the month and day with two digits. */
    String day(int year, int month, int day);

    /** {@code get_search_link}: the query url-encoded, spaces as {@code +}, slashes kept. */
    String search(String query);

    /**
     * {@code get_post_type_archive_link}: for {@code post} the posts page or the home; for a type with an archive its
     * archive path; empty for a type without archive or not registered.
     */
    Optional<String> postTypeArchive(String type);

    /** {@code get_post_format_link}: the format archive ({@code aside}, {@code gallery}, ...). */
    String postFormat(String format);

    /**
     * {@code get_pagenum_link}: page {@code n} of the list the request shows, from the request's path and query
     * string (the home path and any {@code paged} argument removed, a trailing {@code page/N} replaced).
     */
    String paged(String requestPathAndQuery, int n);

    /** Whether the site is in plain mode (empty permalink structure). */
    boolean plain();

    /** Whether pretty links end with a slash. */
    boolean trailingSlashes();
}
