package org.quarkpress.taxonomy;

import java.util.Objects;
import org.quarkpress.core.TermId;

/**
 * A term named in an assignment: by id (a term of the taxonomy on the current site; any other id is skipped) or by
 * name (looked up as a slug, then as a name, and created when missing; a blank name is skipped). An id is never read as
 * a name, nor a name made of digits as an id.
 */
public sealed interface TermRef {

    record Id(TermId term) implements TermRef {
        public Id {
            Objects.requireNonNull(term, "term");
        }
    }

    record Name(String name) implements TermRef {
        public Name {
            Objects.requireNonNull(name, "name");
        }
    }

    static TermRef id(TermId term) {
        return new Id(term);
    }

    static TermRef id(long term) {
        return new Id(TermId.of(term));
    }

    static TermRef name(String name) {
        return new Name(name);
    }
}
