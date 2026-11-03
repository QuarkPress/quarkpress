package org.quarkpress.posts;

import org.quarkpress.core.SiteId;

/**
 * Turns the term filters of a {@link PostQuery} into conditions on posts; implemented by the taxonomy module as one
 * bean. A query with term filters is refused when the application has no provider.
 */
public interface TermConditionProvider {

    /**
     * The condition for {@code clause} on the posts of {@code site}. {@code postId} is the JPQL path of the post id the
     * condition compares (such as {@code p.id}); every parameter the condition binds is named starting with {@code
     * paramPrefix}. A {@link TaxClause.Group} never reaches the provider: the query combines its clauses itself.
     */
    TermCondition condition(SiteId site, TaxClause clause, String postId, String paramPrefix);
}
