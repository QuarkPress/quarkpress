package org.quarkword.core;

/** The state of a site, derived from its flags: deleted beats spam, spam beats archived. */
public enum SiteStatus {
    ACTIVE,
    ARCHIVED,
    SPAM,
    DELETED
}
