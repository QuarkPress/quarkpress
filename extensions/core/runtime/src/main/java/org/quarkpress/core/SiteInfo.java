package org.quarkpress.core;

import java.util.Objects;

/**
 * A site of the network. The flags are independent, as in WordPress; {@link #status()} derives one state from them.
 *
 * @param publicFlag 1 when search engines may index the site, 0 when not; negative values are reserved for privacy
 *     modules
 */
public record SiteInfo(
        SiteId id,
        String domain,
        String path,
        String name,
        int publicFlag,
        boolean archived,
        boolean mature,
        boolean spam,
        boolean deleted) {

    public SiteInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(name, "name");
    }

    public SiteStatus status() {
        if (deleted) {
            return SiteStatus.DELETED;
        }
        if (spam) {
            return SiteStatus.SPAM;
        }
        return archived ? SiteStatus.ARCHIVED : SiteStatus.ACTIVE;
    }
}
