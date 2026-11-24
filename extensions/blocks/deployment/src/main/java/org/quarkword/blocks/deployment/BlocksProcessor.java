package org.quarkword.blocks.deployment;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import org.quarkword.blocks.BlockPatternCategoryProvider;
import org.quarkword.blocks.BlockPatternProvider;
import org.quarkword.blocks.BlockRenderer;
import org.quarkword.blocks.BlockTypeProvider;
import org.quarkword.blocks.runtime.BlocksConfig;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;

class BlocksProcessor {

    static final String FEATURE = "quarkword-blocks";

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

    /** The attributes block supports add, read by {@code BlockSupports} at run time. */
    @BuildStep
    NativeImageResourceBuildItem supportAttributes() {
        return new NativeImageResourceBuildItem("org/quarkword/blocks/block-support-attributes.json");
    }

    @BuildStep
    QuarkWordConfigKeysBuildItem configKeys() {
        return QuarkWordConfigKeysBuildItem.of("blocks", "quarkword.blocks", BlocksConfig.class);
    }
}
