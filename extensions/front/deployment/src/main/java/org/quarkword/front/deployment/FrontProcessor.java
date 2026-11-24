package org.quarkword.front.deployment;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;
import org.quarkword.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkword.front.FrontFeature;
import org.quarkword.front.FrontUrls;
import org.quarkword.front.runtime.FrontConfig;
import org.quarkword.front.runtime.RequestFrontUrls;

class FrontProcessor {

    static final String FEATURE = "quarkword-front";

    /** The per-site switch: off, the site's public pages are not served by this module. */
    static final String SITE_FEATURE = FrontFeature.KEY;

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(FrontUrls.class, RequestFrontUrls.class);
    }

    @BuildStep
    SiteFeatureBuildItem siteFeature() {
        return new SiteFeatureBuildItem(
                SITE_FEATURE,
                "front",
                true,
                "Serves the site's public pages: permalinks, archives, searches and the redirects WordPress makes;"
                        + " when off, the site's front-end requests are not answered by this module");
    }

    @BuildStep
    QuarkWordConfigKeysBuildItem configKeys() {
        return QuarkWordConfigKeysBuildItem.of("front", "quarkword.front", FrontConfig.class);
    }
}
