package org.quarkpress.media.image.vips.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.quarkpress.media.deployment.ImageProcessorBuildItem;

/** The libvips image processor module: its feature, and its name for the processor choice. */
class MediaImageVipsProcessor {

    static final String FEATURE = "quarkpress-media-image-vips";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /** Names this module for {@code quarkpress.media.image-processor}. */
    @BuildStep
    ImageProcessorBuildItem imageProcessor() {
        return new ImageProcessorBuildItem("vips");
    }
}
