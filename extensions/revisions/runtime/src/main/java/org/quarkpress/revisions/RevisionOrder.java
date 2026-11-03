package org.quarkpress.revisions;

/**
 * What a {@link RevisionQuery} orders by, as WordPress's REST revisions controller offers it. {@code DATE}: by time,
 * then by id, both in the query's direction. {@code ID}: by id. {@code TITLE}: by title, then by id. {@code SLUG}:
 * by kind, as the names {@code {post}-autosave-v1} and {@code {post}-revision-v1} sort (autosaves before regular
 * revisions ascending), then by id ascending whatever the direction. {@code INCLUDE}: the order of the query's
 * {@code include} list, the direction ignored. {@code INCLUDE_SLUGS}: by time ascending whatever the direction, as
 * WordPress falls back to for a slug list it does not have. {@code RELEVANCE}: WordPress's search rank over the
 * query's {@code search} (the whole search in the title, then every term in the title, then any term in the title,
 * then the whole search in the excerpt and the content), then by time, newest first.
 */
public enum RevisionOrder {
    DATE,
    ID,
    TITLE,
    SLUG,
    INCLUDE,
    INCLUDE_SLUGS,
    RELEVANCE
}
