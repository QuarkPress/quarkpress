package org.quarkpress.restwp.deployment;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.restwp.RestWpV2Feature;
import org.quarkpress.restwp.runtime.RestWpConfig;

class RestWpV2Processor {

    static final String FEATURE = "quarkpress-rest-wp-v2";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    SiteFeatureBuildItem siteFeature() {
        return new SiteFeatureBuildItem(
                RestWpV2Feature.KEY,
                "rest-wp-v2",
                true,
                "Serves WordPress's REST API (wp/v2) for the site; when off, every REST URL of the site answers 404"
                        + " rest_no_route");
    }

    @BuildStep
    QuarkPressConfigKeysBuildItem configKeys() {
        return QuarkPressConfigKeysBuildItem.of("rest-wp-v2", "quarkpress.rest-wp-v2", RestWpConfig.class);
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(RestWpConfig.class);
    }
}
