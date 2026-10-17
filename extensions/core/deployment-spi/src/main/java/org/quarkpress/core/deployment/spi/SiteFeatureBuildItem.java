package org.quarkpress.core.deployment.spi;

import io.quarkus.builder.item.MultiBuildItem;
import org.quarkpress.core.FeatureDescriptor;

/** Declares a per-site feature. Produce one from the deployment module of the extension that owns the feature. */
public final class SiteFeatureBuildItem extends MultiBuildItem {

    private final FeatureDescriptor descriptor;

    public SiteFeatureBuildItem(String key, String module, boolean defaultEnabled, String description) {
        this.descriptor = new FeatureDescriptor(key, module, defaultEnabled, description);
    }

    public FeatureDescriptor descriptor() {
        return descriptor;
    }

    public String key() {
        return descriptor.key();
    }
}
