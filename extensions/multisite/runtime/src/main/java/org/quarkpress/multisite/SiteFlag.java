package org.quarkpress.multisite;

/** The independent flags of a site, as WordPress has them. */
public enum SiteFlag {
    /** Archived: kept but not shown (WordPress {@code archived}). */
    ARCHIVED,
    /** For mature audiences (WordPress {@code mature}). */
    MATURE,
    /** Marked as spam (WordPress {@code spam}). */
    SPAM,
    /** Deactivated, not deleted: the rows stay (WordPress {@code deleted}). */
    DELETED
}
