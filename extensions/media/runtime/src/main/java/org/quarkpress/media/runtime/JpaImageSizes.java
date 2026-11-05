package org.quarkpress.media.runtime;

import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.quarkpress.core.Filters;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteOptions;
import org.quarkpress.media.ImageSizeDefinition;
import org.quarkpress.media.ImageSizeProvider;
import org.quarkpress.media.ImageSizes;

/**
 * The current site's image sizes: the providers' registrations, read once at start-up, resolved against the site's
 * options on each call (the options come from the request's cache).
 */
@Startup
@ApplicationScoped
public class JpaImageSizes implements ImageSizes {

    private final SiteContext site;
    private final Map<String, ImageSizeDefinition> registrations;

    @Inject
    public JpaImageSizes(SiteContext site, @All List<InstanceHandle<ImageSizeProvider>> providers) {
        this.site = site;
        this.registrations = BuiltInImageSizes.registrations(Filters.ordered(providers));
    }

    @Override
    public Map<String, ImageSizeDefinition> registered() {
        SiteOptions options = site.options();
        return BuiltInImageSizes.resolve(registrations, options::get);
    }
}
