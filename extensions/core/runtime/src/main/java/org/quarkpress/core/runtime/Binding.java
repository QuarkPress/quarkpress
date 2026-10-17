package org.quarkpress.core.runtime;

import java.util.Objects;
import org.quarkpress.core.SiteId;

/** What the current request is bound to: one site, the whole network, or nothing. */
public record Binding(Kind kind, SiteId site) {

    public enum Kind {
        SITE,
        NETWORK,
        UNRESOLVED
    }

    public static final Binding NETWORK = new Binding(Kind.NETWORK, null);
    public static final Binding UNRESOLVED = new Binding(Kind.UNRESOLVED, null);

    public Binding {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.SITE) != (site != null)) {
            throw new IllegalArgumentException("a site binding needs a site and only a site binding has one");
        }
    }

    public static Binding site(SiteId site) {
        return new Binding(Kind.SITE, Objects.requireNonNull(site, "site"));
    }
}
