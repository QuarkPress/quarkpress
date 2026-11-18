package org.quarkpress.media.image.vips.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.ExcludedTypeBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigBuilderBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import org.quarkpress.media.deployment.ImageProcessorBuildItem;
import org.quarkpress.media.image.vips.runtime.VipsConfigBuilder;
import org.quarkpress.media.image.vips.runtime.VipsImageProcessor;
import org.quarkpress.media.runtime.MediaBuildTimeConfig;

/**
 * The libvips image processor module: its feature, its name for the processor choice, its processor bean, the
 * defaults that keep libvips's input limits at or above QuarkPress's, and the probe images a native image must hold.
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

    /**
     * The images the processor's start-up probe decodes to find what this libvips reads and writes (a PNG, and an HEIC
     * that tells whether an HEVC decoder is installed). A native image holds no resource it is not told about.
     */
    @BuildStep
    NativeImageResourceBuildItem probeResources() {
        return new NativeImageResourceBuildItem(
                "org/quarkpress/media/image/vips/probe.heic", "org/quarkpress/media/image/vips/probe.png");
    }
}
