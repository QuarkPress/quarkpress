package org.quarkpress.posts;

/**
 * Sanitizes a post's title, content and excerpt when it is saved, by the caller's {@link SaveFilters}. The default
 * bean behaves as WordPress's save filters with lib-wp-kses; an application replaces it with its own bean. Every
 * save runs every field through it, including fields an update does not change.
 */
public interface ContentSanitizer {

    String title(String title, SaveFilters filters);

    String content(String content, SaveFilters filters);

    String excerpt(String excerpt, SaveFilters filters);
}
