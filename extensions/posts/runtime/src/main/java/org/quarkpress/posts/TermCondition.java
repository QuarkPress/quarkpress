package org.quarkpress.posts;

import java.util.Map;
import java.util.Objects;

/** What a {@link TaxClause} means for a post, as a {@link TermConditionProvider} answers it. */
public sealed interface TermCondition {

    /**
     * A JPQL condition over the post id path the provider was given, with every value bound as a parameter named in
     * {@code params} (the names start with the prefix the provider was given, without the colon). The text must hold
     * no value of its own, and must run on every supported database.
     */
    record Jpql(String text, Map<String, Object> params) implements TermCondition {
        public Jpql {
            Objects.requireNonNull(text, "text");
            params = Map.copyOf(params);
        }
    }

    /** The clause matches no post. */
    record Nothing() implements TermCondition {}

    /** The clause applies no condition and is skipped. */
    record None() implements TermCondition {}
}
