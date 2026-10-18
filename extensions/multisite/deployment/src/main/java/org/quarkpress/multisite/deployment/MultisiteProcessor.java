package org.quarkpress.multisite.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.vertx.http.deployment.NonApplicationRootPathBuildItem;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.multisite.runtime.MultisiteConfig;
import org.quarkpress.multisite.runtime.MultisiteRecorder;

class MultisiteProcessor {

    static final String FEATURE = "quarkpress-multisite";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    MigrationLocationBuildItem migrations() {
        return new MigrationLocationBuildItem("multisite", 2);
    }

    @BuildStep
    QuarkPressConfigKeysBuildItem configKeys() {
        return QuarkPressConfigKeysBuildItem.of("quarkpress.multisite", MultisiteConfig.class);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void nonApplicationRoot(NonApplicationRootPathBuildItem root, MultisiteRecorder recorder) {
        recorder.nonApplicationRoot(root.getNonApplicationRootPath());
    }
}
