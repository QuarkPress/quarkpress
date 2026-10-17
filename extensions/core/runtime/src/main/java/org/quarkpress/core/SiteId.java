package org.quarkpress.core;

import java.util.Optional;

/** Identifies a site of the network. Ids are positive; the value is the database key of the site. */
public record SiteId(long value) {

    public SiteId {
        Ids.requirePositive(value, "site");
    }

    public static SiteId of(long value) {
        return new SiteId(value);
    }

    /** Reads a canonical decimal id such as {@code "42"}; anything else is empty. */
    public static Optional<SiteId> parse(CharSequence text) {
        return Ids.parse(text).map(SiteId::new);
    }
}
