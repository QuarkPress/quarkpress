package org.quarkword.media.runtime;

import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.quarkword.core.Filters;
import org.quarkword.core.SiteContext;
import org.quarkword.core.SiteOptions;
import org.quarkword.media.ImageSizeDefinition;
import org.quarkword.media.ImageSizeProvider;
import org.quarkword.media.ImageSizes;

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
