package org.quarkpress.core;

/** Shared checks of the site events. */
final class SiteEvents {

    private SiteEvents() {}

    static void requireSame(SiteId site, SiteInfo record) {
        if (!site.equals(record.id())) {
            throw new IllegalArgumentException("an event about site " + site.value() + " carries the record of site "
                    + record.id().value());
        }
    }
}
