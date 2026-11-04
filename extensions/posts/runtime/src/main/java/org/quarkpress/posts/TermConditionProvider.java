package org.quarkpress.posts;

import java.util.function.Supplier;
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

    /**
     * Runs {@code clauses}, which asks for the conditions of every clause of one query on the current thread, so that
     * the provider may bound what the query's clauses bind together (the databases refuse a statement with too many
     * parameters). By default it just runs them.
     */
    default <T> T forQuery(Supplier<T> clauses) {
        return clauses.get();
    }
}
