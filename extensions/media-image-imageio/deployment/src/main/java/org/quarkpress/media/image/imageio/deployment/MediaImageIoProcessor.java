package org.quarkpress.media.image.imageio.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedPackageBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import java.util.List;
import org.quarkpress.media.image.imageio.runtime.ImageIoProcessor;

/**
 * The ImageIO image processor bean, and what a native image needs for ImageIO to find and run the TwelveMonkeys
 * plugins; quarkus-awt handles ImageIO and Java2D themselves.
 */
class MediaImageIoProcessor {

    static final String FEATURE = "quarkpress-media-image-imageio";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /** The processor replaces the media module's default one, which handles no image type. */
    @BuildStep
    AdditionalBeanBuildItem beans() {
        return AdditionalBeanBuildItem.unremovableOf(ImageIoProcessor.class);
    }

    /**
     * The colour profiles TwelveMonkeys loads as resources: the Adobe RGB (1998) compatible profile it substitutes for
     * that colour space, and the per-system tables naming where the system's profiles are. A native image holds no
     * resource it is not told about.
     */
    @BuildStep
    NativeImageResourceBuildItem iccResources() {
        return new NativeImageResourceBuildItem(
                "profiles/ClayRGB1998.icc",
                "com/twelvemonkeys/imageio/color/icc_profiles_lnx.properties",
                "com/twelvemonkeys/imageio/color/icc_profiles_osx.properties",
                "com/twelvemonkeys/imageio/color/icc_profiles_win.properties");
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
