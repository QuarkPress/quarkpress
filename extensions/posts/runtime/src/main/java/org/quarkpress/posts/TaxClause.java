package org.quarkpress.posts;

import java.util.List;
import java.util.Objects;

/**
 * One clause of a {@link TaxQuery}, as WordPress's tax query holds it. The posts module keeps no taxonomy dependency:
 * what a clause means for a post comes from the application's {@link TermConditionProvider}, which the taxonomy module
 * implements. A {@link Group} combines clauses one level down and never holds another group.
 */
public sealed interface TaxClause {

    /** How a {@link Terms} clause matches: a post with any of the terms, with all of them, or with none of them. */
    enum Operator {
        IN,
        AND,
        NOT_IN
    }

    /** How the clauses of a query or group combine. */
    enum Relation {
        AND,
        OR
    }

    /**
     * Terms of one taxonomy by id. With {@code includeChildren} each term stands for itself and all its descendants.
     * The provider decides what unknown ids mean: WordPress drops them, and a clause left with no term matches nothing
     * for {@link Operator#IN}, nothing for {@link Operator#AND} as soon as one id is unknown, and applies no condition
     * for {@link Operator#NOT_IN}.
     */
    record Terms(String taxonomy, List<Long> termIds, boolean includeChildren, Operator operator) implements TaxClause {
        public Terms {
            Objects.requireNonNull(taxonomy, "taxonomy");
            termIds = List.copyOf(termIds);
            Objects.requireNonNull(operator, "operator");
        }
    }

    /** Posts with any of the terms named by slug in one taxonomy; with no slug the clause adds no condition. */
    record Slugs(String taxonomy, List<String> slugs) implements TaxClause {
        public Slugs {
            Objects.requireNonNull(taxonomy, "taxonomy");
            slugs = List.copyOf(slugs);
        }
    }

    /** Posts with no term of the taxonomy. */
    record NotExists(String taxonomy) implements TaxClause {
        public NotExists {
            Objects.requireNonNull(taxonomy, "taxonomy");
        }
    }

    /** Clauses combined by {@code relation} that count as one clause of the query; a group never holds a group. */
    record Group(Relation relation, List<TaxClause> clauses) implements TaxClause {
        public Group {
            Objects.requireNonNull(relation, "relation");
            clauses = List.copyOf(clauses);
            for (TaxClause clause : clauses) {
                if (clause instanceof Group) {
                    throw new IllegalArgumentException("term filter groups do not nest");
                }
            }
        }
    }

    /**
     * Whether the clause asks for posts that have terms: an in or all-of terms clause, or a slugs clause naming a
     * slug.
     */
    default boolean positive() {
        return switch (this) {
            case Terms terms -> terms.operator() != Operator.NOT_IN;
            case Slugs slugs -> !slugs.slugs().isEmpty();
            case NotExists notExists -> false;
            case Group group -> group.clauses().stream().anyMatch(TaxClause::positive);
        };
    }
}
