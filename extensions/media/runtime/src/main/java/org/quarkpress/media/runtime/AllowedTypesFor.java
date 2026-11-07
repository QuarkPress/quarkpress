package org.quarkpress.media.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.media.MimeTypes;
import org.quarkpress.posts.runtime.Acting;

/** The upload types of the acting user on the current site. */
@ApplicationScoped
public class AllowedTypesFor {

    @Inject
    MimeTypes mimeTypes;

    @Inject
    MediaConfig config;

    @Inject
    SiteContext site;

    @Inject
    Acting acting;

    @Inject
    NetworkUploadTypes networkTypes;

    /**
     * The acting user's upload types on the current site: the site's type list, with {@code htm|html} and {@code js}
     * only for a user who may post unfiltered HTML (on a network, super admins only), without the configured denied
     * extensions, and on a network only the patterns the network's list of upload types keeps.
     */
    public AllowedTypes current() {
        SiteId siteId = site.siteId();
        boolean unfilteredHtml = acting.can(siteId, "unfiltered_html", Optional.empty());
        return AllowedTypes.of(
                mimeTypes.patterns(), unfilteredHtml, config.deniedExtensions().orElse(List.of()), networkTypes.list());
    }
}
