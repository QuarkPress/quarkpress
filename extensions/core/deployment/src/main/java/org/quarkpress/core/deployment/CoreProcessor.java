package org.quarkpress.core.deployment;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.core.runtime.SiteResolution;

class CoreProcessor {

    static final String FEATURE = "quarkpress-core";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(SiteBinding.class, SiteResolution.class);
    }
}
