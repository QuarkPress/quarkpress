package org.quarkpress.taxonomy;

/** Whether a caller may list the terms of a post in a taxonomy. */
public enum PostTermsAccess {
    /** There is no such post on the bound site. */
    MISSING_POST,
    ALLOWED,
    DENIED
}
