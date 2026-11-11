package org.quarkpress.blocks.deployment;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import org.quarkpress.blocks.BlockPatternCategoryProvider;
import org.quarkpress.blocks.BlockPatternProvider;
import org.quarkpress.blocks.BlockRenderer;
import org.quarkpress.blocks.BlockTypeProvider;
import org.quarkpress.blocks.runtime.BlocksConfig;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;

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

    /** The attributes block supports add, read by {@code BlockSupports} at run time. */
    @BuildStep
    NativeImageResourceBuildItem supportAttributes() {
        return new NativeImageResourceBuildItem("org/quarkpress/blocks/block-support-attributes.json");
    }

    @BuildStep
    QuarkPressConfigKeysBuildItem configKeys() {
        return QuarkPressConfigKeysBuildItem.of("blocks", "quarkpress.blocks", BlocksConfig.class);
    }
}
