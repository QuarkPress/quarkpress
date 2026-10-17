package org.quarkpress.core.runtime;

import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.quarkpress.core.FeatureDescriptor;
import org.quarkpress.core.FeatureRegistry;
import org.quarkpress.core.FeatureSettings;
import org.quarkpress.core.FeatureValue;
import org.quarkpress.core.FeatureValue.Source;
import org.quarkpress.core.SiteFeatures;
import org.quarkpress.core.SiteId;

/** Computes the features of a site; inside a request the result is cached until a switch changes. */
@ApplicationScoped
public class FeatureResolver {

    @Inject
    FeatureRegistry registry;

    @Inject
    FeatureSettings settings;

    @Inject
    RequestFeatureCache cache;

    /** The features of a site; a null site is rejected (a SiteId is always a real, positive id). */
    public SiteFeatures forSite(SiteId site) {
        Objects.requireNonNull(site, "site");
        if (!Arc.container().requestContext().isActive()) {
            return new ResolvedSiteFeatures(compute(site));
        }
        return new ResolvedSiteFeatures(cache.get(site, this::compute));
    }

    public SiteFeatures noSite() {
        Map<String, FeatureValue> values = new LinkedHashMap<>();
        for (FeatureDescriptor feature : registry.all()) {
            values.put(feature.key(), new FeatureValue(feature.key(), false, Source.NO_SITE));
        }
        return new ResolvedSiteFeatures(Collections.unmodifiableMap(values));
    }

    private Map<String, FeatureValue> compute(SiteId site) {
        Map<String, Boolean> siteValues = settings.siteValues(site);
        Map<String, Boolean> networkValues = settings.networkDefaults();
        Map<String, FeatureValue> values = new LinkedHashMap<>();
        for (FeatureDescriptor feature : registry.all()) {
            values.put(feature.key(), FeatureValues.resolve(feature, siteValues, networkValues));
        }
        return Collections.unmodifiableMap(values);
    }
}
