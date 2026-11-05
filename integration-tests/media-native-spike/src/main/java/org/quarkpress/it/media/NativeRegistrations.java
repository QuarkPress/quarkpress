package org.quarkpress.it.media;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What a native executable needs so that ImageIO's own {@link java.util.ServiceLoader} lookup finds the TwelveMonkeys
 * plugins. Quarkus turns GraalVM's automatic service registration off ({@code -H:-UseServiceLoaderFeature}), so the
 * {@code META-INF/services/javax.imageio.spi.*} files are included as resources (application.properties) and the
 * provider classes listed in them are registered here for reflective instantiation. A QuarkPress media extension would
 * produce {@code ServiceProviderBuildItem}s instead.
 */
@RegisterForReflection(
        classNames = {
            // imageio-jpeg: META-INF/services/javax.imageio.spi.ImageReaderSpi and ImageWriterSpi
            "com.twelvemonkeys.imageio.plugins.jpeg.JPEGImageReaderSpi",
            "com.twelvemonkeys.imageio.plugins.jpeg.JPEGImageWriterSpi",
            // imageio-webp: META-INF/services/javax.imageio.spi.ImageReaderSpi
            "com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi",
            // imageio-core: META-INF/services/javax.imageio.spi.ImageInputStreamSpi
            "com.twelvemonkeys.imageio.stream.BufferedFileImageInputStreamSpi",
            "com.twelvemonkeys.imageio.stream.BufferedRAFImageInputStreamSpi",
            "com.twelvemonkeys.imageio.stream.BufferedInputStreamImageInputStreamSpi",
            "com.twelvemonkeys.imageio.color.ProfileDeferralActivator$Spi",
            // Class.forName in ProfileDeferralActivator
            "java.awt.image.ColorConvertOp"
        })
final class NativeRegistrations {

    private NativeRegistrations() {}
}
