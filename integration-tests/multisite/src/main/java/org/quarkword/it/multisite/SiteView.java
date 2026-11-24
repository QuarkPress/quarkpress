package org.quarkword.it.multisite;

import org.quarkword.core.SiteInfo;

public record SiteView(long id, String domain, String path, String name, String status, int publicFlag) {

    static SiteView of(SiteInfo site) {
        return new SiteView(
                site.id().value(),
                site.domain(),
                site.path(),
                site.name(),
                site.status().name(),
                site.publicFlag());
    }
}
