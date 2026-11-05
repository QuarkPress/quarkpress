package org.quarkpress.media.image.imageio.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedPackageBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import java.util.List;

/**
 * What a native image needs for ImageIO to find and run the TwelveMonkeys plugins; quarkus-awt handles ImageIO and
 * Java2D themselves.
 */
class MediaImageIoProcessor {

    static final String FEATURE = "quarkpress-media-image-imageio";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * The TwelveMonkeys providers, as their jars' service files list them: ImageIO's registry finds plugins through
     * {@code ServiceLoader}, and Quarkus does not register service files in a native image by itself. The input
     * stream providers include TwelveMonkeys' colour profile activator, which it hooks in as one.
     */
    @BuildStep
    List<ServiceProviderBuildItem> imageIoServices() {
        return List.of(
                new ServiceProviderBuildItem(
                        "javax.imageio.spi.ImageReaderSpi",
                        "com.twelvemonkeys.imageio.plugins.jpeg.JPEGImageReaderSpi",
                        "com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi"),
                new ServiceProviderBuildItem(
                        "javax.imageio.spi.ImageWriterSpi",
                        "com.twelvemonkeys.imageio.plugins.jpeg.JPEGImageWriterSpi"),
                new ServiceProviderBuildItem(
                        "javax.imageio.spi.ImageInputStreamSpi",
                        "com.twelvemonkeys.imageio.stream.BufferedFileImageInputStreamSpi",
                        "com.twelvemonkeys.imageio.stream.BufferedRAFImageInputStreamSpi",
                        "com.twelvemonkeys.imageio.stream.BufferedInputStreamImageInputStreamSpi",
                        "com.twelvemonkeys.imageio.color.ProfileDeferralActivator$Spi"));
    }

    /** TwelveMonkeys' colour profile activator loads this class by name. */
    @BuildStep
    ReflectiveClassBuildItem reflection() {
        return ReflectiveClassBuildItem.builder("java.awt.image.ColorConvertOp").build();
    }

    /**
     * TwelveMonkeys' static initializers touch {@code java.awt} and {@code javax.imageio}, which quarkus-awt
     * initializes at run time, so its classes are initialized at run time too.
     */
    @BuildStep
    RuntimeInitializedPackageBuildItem runtimeInit() {
        return new RuntimeInitializedPackageBuildItem("com.twelvemonkeys");
    }
}
