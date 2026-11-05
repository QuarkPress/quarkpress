package org.quarkpress.media.image.imageio.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.quarkpress.media.image.imageio.runtime.ImageIoProcessor;

/** The ImageIO build steps that need no application: what a native image needs to find the TwelveMonkeys plugins. */
class ImageIoRegistrationsTest {

    @Test
    void theTwelveMonkeysProvidersAreRegisteredAsServices() {
        Map<String, List<String>> services = new MediaImageIoProcessor()
                .imageIoServices().stream()
                        .collect(Collectors.toMap(
                                ServiceProviderBuildItem::serviceDescriptorFile, ServiceProviderBuildItem::providers));
        assertThat(services.get("META-INF/services/javax.imageio.spi.ImageReaderSpi"))
                .containsExactlyInAnyOrder(
                        "com.twelvemonkeys.imageio.plugins.jpeg.JPEGImageReaderSpi",
                        "com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi");
        assertThat(services.get("META-INF/services/javax.imageio.spi.ImageWriterSpi"))
                .containsExactly("com.twelvemonkeys.imageio.plugins.jpeg.JPEGImageWriterSpi");
        assertThat(services.get("META-INF/services/javax.imageio.spi.ImageInputStreamSpi"))
                .containsExactlyInAnyOrder(
                        "com.twelvemonkeys.imageio.stream.BufferedFileImageInputStreamSpi",
                        "com.twelvemonkeys.imageio.stream.BufferedRAFImageInputStreamSpi",
                        "com.twelvemonkeys.imageio.stream.BufferedInputStreamImageInputStreamSpi",
                        "com.twelvemonkeys.imageio.color.ProfileDeferralActivator$Spi");
        assertThat(services).hasSize(3);
    }

    @Test
    void twelveMonkeysIsInitializedAtRunTime() {
        assertThat(new MediaImageIoProcessor().runtimeInit().getPackageName()).isEqualTo("com.twelvemonkeys");
    }

    @Test
    void colorConvertOpIsRegisteredForReflection() throws ClassNotFoundException {
        assertThat(new MediaImageIoProcessor().reflection().getClassNames())
                .containsExactly("java.awt.image.ColorConvertOp");
        Class.forName("java.awt.image.ColorConvertOp", false, getClass().getClassLoader());
    }

    @Test
    void theColourProfileResourcesAreInTheNativeImage() {
        NativeImageResourceBuildItem item = new MediaImageIoProcessor().iccResources();
        assertThat(item.getResources())
                .containsExactlyInAnyOrder(
                        "profiles/ClayRGB1998.icc",
                        "com/twelvemonkeys/imageio/color/icc_profiles_lnx.properties",
                        "com/twelvemonkeys/imageio/color/icc_profiles_osx.properties",
                        "com/twelvemonkeys/imageio/color/icc_profiles_win.properties");
        for (String resource : item.getResources()) {
            assertThat(getClass().getClassLoader().getResource(resource))
                    .as("%s on the class path", resource)
                    .isNotNull();
        }
    }

    @Test
    void theImageIoProcessorIsABean() {
        AdditionalBeanBuildItem item = new MediaImageIoProcessor().beans();
        assertThat(item.getBeanClasses()).containsExactly(ImageIoProcessor.class.getName());
        assertThat(item.isRemovable()).isFalse();
    }

    /** A build step without its annotation would compile, pass the tests above and never run in a build. */
    @Test
    void everyBuildStepMethodCarriesTheAnnotation() {
        List<String> steps = new java.util.ArrayList<>();
        for (Method method : MediaImageIoProcessor.class.getDeclaredMethods()) {
            if (method.isSynthetic() || Modifier.isPrivate(method.getModifiers())) {
                continue;
            }
            assertThat(method.isAnnotationPresent(BuildStep.class))
                    .as("%s carries @BuildStep", method.getName())
                    .isTrue();
            steps.add(method.getName());
        }
        assertThat(steps)
                .containsExactlyInAnyOrder(
                        "feature", "imageIoServices", "reflection", "runtimeInit", "iccResources", "beans");
    }

    @Test
    void everyRegisteredProviderIsListedInItsJarsServiceFile() throws IOException {
        for (ServiceProviderBuildItem item : new MediaImageIoProcessor().imageIoServices()) {
            Set<String> listed = listedProviders(item.serviceDescriptorFile());
            assertThat(listed)
                    .as("providers listed in the class path's %s", item.serviceDescriptorFile())
                    .containsAll(item.providers());
        }
    }

    /** The provider class names in every {@code descriptor} on the class path, without comments and blank lines. */
    private static Set<String> listedProviders(String descriptor) throws IOException {
        Set<String> providers = new TreeSet<>();
        Enumeration<URL> files = ImageIoRegistrationsTest.class.getClassLoader().getResources(descriptor);
        while (files.hasMoreElements()) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(files.nextElement().openStream(), StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    int comment = line.indexOf('#');
                    String provider = (comment < 0 ? line : line.substring(0, comment)).strip();
                    if (!provider.isEmpty()) {
                        providers.add(provider);
                    }
                }
            }
        }
        return providers;
    }
}
