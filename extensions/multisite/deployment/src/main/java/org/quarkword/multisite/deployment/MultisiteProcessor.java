package org.quarkword.multisite.deployment;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.vertx.http.deployment.NonApplicationRootPathBuildItem;
import org.quarkword.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;
import org.quarkword.multisite.SiteDomains;
import org.quarkword.multisite.Sites;
import org.quarkword.multisite.runtime.MultisiteConfig;
import org.quarkword.multisite.runtime.MultisiteRecorder;

class MultisiteProcessor {

    static final String FEATURE = "quarkword-multisite";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    MigrationLocationBuildItem migrations() {
        return new MigrationLocationBuildItem("multisite", 2);
    }

    @BuildStep
    QuarkWordConfigKeysBuildItem configKeys() {
        return QuarkWordConfigKeysBuildItem.of("multisite", "quarkword.multisite", MultisiteConfig.class);
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(Sites.class, SiteDomains.class);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void nonApplicationRoot(NonApplicationRootPathBuildItem root, MultisiteRecorder recorder) {
        recorder.nonApplicationRoot(root.getNonApplicationRootPath());
    }
}
