package org.quarkpress.multisite;

/**
 * Changes to a site's address or name; a null component stays as it is. Domain and path are normalized as for a new
 * site.
 */
public record SiteChanges(String domain, String path, String name) {

    public static SiteChanges none() {
        return new SiteChanges(null, null, null);
    }

    public SiteChanges withDomain(String value) {
        return new SiteChanges(value, path, name);
    }

    public SiteChanges withPath(String value) {
        return new SiteChanges(domain, value, name);
    }

    public SiteChanges withName(String value) {
        return new SiteChanges(domain, path, value);
    }
}
