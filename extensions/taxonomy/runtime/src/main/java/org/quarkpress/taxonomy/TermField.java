package org.quarkpress.taxonomy;

/** The field a term is looked up by (WordPress's {@code get_term_by}). */
public enum TermField {
    /** The id, given as decimal text. */
    ID,
    /** The slug; the value is sanitized as a slug first, so "News" finds {@code news}. */
    SLUG,
    /** The name; the value is sanitized as a stored name and compared ignoring case. */
    NAME
}
