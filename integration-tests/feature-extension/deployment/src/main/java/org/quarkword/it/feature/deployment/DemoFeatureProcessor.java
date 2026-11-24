package org.quarkword.it.feature.deployment;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.quarkword.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkword.it.feature.DemoFeatures;

class DemoFeatureProcessor {

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem("quarkword-it-feature");
    }

    @BuildStep
    void features(BuildProducer<SiteFeatureBuildItem> features) {
        features.produce(new SiteFeatureBuildItem(DemoFeatures.DEMO, DemoFeatures.MODULE, false, "Off by default"));
        features.produce(new SiteFeatureBuildItem(DemoFeatures.DEMO_ON, DemoFeatures.MODULE, true, "On by default"));
    }
}
