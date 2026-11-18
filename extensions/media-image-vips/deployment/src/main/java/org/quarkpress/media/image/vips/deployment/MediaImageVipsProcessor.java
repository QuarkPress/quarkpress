package org.quarkpress.media.image.vips.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.ExcludedTypeBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigBuilderBuildItem;
import org.quarkpress.media.deployment.ImageProcessorBuildItem;
import org.quarkpress.media.image.vips.runtime.VipsConfigBuilder;
import org.quarkpress.media.image.vips.runtime.VipsImageProcessor;
import org.quarkpress.media.runtime.MediaBuildTimeConfig;

/**
 * The libvips image processor module: its feature, its name for the processor choice, its processor bean, and the
 * defaults that keep libvips's input limits at or above QuarkPress's.
 */
class MediaImageVipsProcessor {

    static final String FEATURE = "quarkpress-media-image-vips";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * The libvips processor, which takes precedence over any other image processor; left out of the application when
     * {@code quarkpress.media.image-processor} chooses ImageIO.
     */
    @BuildStep
    void beans(
            MediaBuildTimeConfig config,
            BuildProducer<AdditionalBeanBuildItem> beans,
            BuildProducer<ExcludedTypeBuildItem> excluded) {
        if (config.imageProcessor().filter("imageio"::equals).isPresent()) {
            excluded.produce(new ExcludedTypeBuildItem(VipsImageProcessor.class.getName()));
            return;
        }
        beans.produce(AdditionalBeanBuildItem.unremovableOf(VipsImageProcessor.class));
    }

    /** Raises libvips's input limits, when they are not configured, to QuarkPress's own. */
    @BuildStep
    RunTimeConfigBuilderBuildItem configBuilder() {
        return new RunTimeConfigBuilderBuildItem(VipsConfigBuilder.class);
    }

    /** Names this module for {@code quarkpress.media.image-processor}. */
    @BuildStep
    ImageProcessorBuildItem imageProcessor() {
        return new ImageProcessorBuildItem("vips");
    }
}
