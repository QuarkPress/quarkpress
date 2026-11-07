package org.quarkpress.posts;

import com.fasterxml.jackson.databind.JsonNode;
import org.quarkpress.core.SiteId;

/**
 * Adds a module's own condition to post queries without the posts module knowing the module: a query names the
 * condition by {@link #key()} with {@link PostQuery.Builder#condition}, and the bean providing that key turns the
 * query's argument into a condition on posts. Implemented as CDI beans; each key is provided by one bean only, and two
 * beans providing the same key stop the application at start.
 */
public interface PostConditionProvider {

    /** The key queries name this condition by, such as {@code quarkpress.media.mime-type}. */
    String key();

    /**
     * The condition {@code argument} asks for on the posts of {@code site}, answered as a term condition is: a JPQL
     * condition, a condition that matches nothing (the query then finds nothing), or none (skipped). {@code postId} is
     * the JPQL path of the post id the condition compares (such as {@code p.id}); every parameter the condition binds
     * is named starting with {@code paramPrefix}. An argument the provider cannot read is refused with {@link
     * IllegalArgumentException}.
     */
    TermCondition condition(SiteId site, JsonNode argument, String postId, String paramPrefix);
}
