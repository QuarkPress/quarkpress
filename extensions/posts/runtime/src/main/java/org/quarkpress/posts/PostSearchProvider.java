package org.quarkpress.posts;

import org.quarkpress.core.SiteId;

/**
 * Adds a module's own searchable column to post searches without the posts module knowing the module: a query names
 * the column by {@link #key()} with {@link PostQuery.Builder#searchAlso}, and for each search term the bean providing
 * that key says which posts the term matches in its column. A post matches a term when the term is in one of the
 * searched post columns or in the provided column; for an excluded term, the post must not match it in the provided
 * column either. Implemented as CDI beans; each key is provided by one bean only, and two beans providing the same key
 * stop the application at start.
 */
public interface PostSearchProvider {

    /** The key queries name this column by, such as {@code quarkpress.media.file-path}. */
    String key();

    /**
     * The posts of {@code site} whose column holds {@code term}: as a part of it, or as the whole of it when {@code
     * exact} (WordPress's exact search), answered as a term condition is: a JPQL condition, a condition that matches
     * nothing or none (both leave the term to the post columns). {@code postId} is the JPQL path of the post id the
     * condition compares (such as {@code p.id}); every parameter the condition binds is named starting with {@code
     * paramPrefix}.
     */
    TermCondition term(SiteId site, String term, boolean exact, String postId, String paramPrefix);
}
