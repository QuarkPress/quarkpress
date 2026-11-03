package org.quarkpress.posts;

import java.util.List;
import java.util.Objects;

/**
 * The term filters of a query, as WordPress's tax query: clauses combined by {@code relation}. The conditions come from
 * the application's {@link TermConditionProvider}; see {@link PostQuery} for how they combine. No clauses means no
 * term filter.
 */
public record TaxQuery(TaxClause.Relation relation, List<TaxClause> clauses) {

    /** No term filter. */
    public static final TaxQuery NONE = new TaxQuery(TaxClause.Relation.AND, List.of());

    public TaxQuery {
        Objects.requireNonNull(relation, "relation");
        clauses = List.copyOf(clauses);
    }

    /** Whether the query has no clause. */
    public boolean isEmpty() {
        return clauses.isEmpty();
    }

    /**
     * Whether a clause asks for posts that have terms (an in or all-of terms clause, a slugs clause, or a group holding
     * one): the query is then an archive, which puts no sticky posts first.
     */
    public boolean positive() {
        return clauses.stream().anyMatch(TaxClause::positive);
    }
}
