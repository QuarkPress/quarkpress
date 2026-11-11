package org.quarkpress.blocks.deployment;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.quarkpress.blocks.BlockPatternCategoryProvider;
import org.quarkpress.blocks.BlockPatternProvider;
import org.quarkpress.blocks.BlockRenderer;
import org.quarkpress.blocks.BlockTypeProvider;

class BlocksProcessor {

    static final String FEATURE = "quarkpress-blocks";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /** The registries look their providers and renderers up at start-up, so none of them may be removed. */
    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(
                BlockTypeProvider.class,
                BlockRenderer.class,
                BlockPatternProvider.class,
                BlockPatternCategoryProvider.class);
    }
}
