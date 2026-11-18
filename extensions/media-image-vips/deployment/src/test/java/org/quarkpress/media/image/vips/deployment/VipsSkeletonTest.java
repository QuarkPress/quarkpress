package org.quarkpress.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.ExcludedTypeBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.media.image.vips.runtime.VipsConfigBuilder;
import org.quarkpress.media.image.vips.runtime.VipsImageProcessor;
import org.quarkpress.media.runtime.MediaBuildTimeConfig;

/** The vips build steps that need no application. */
class VipsSkeletonTest {

    @Test
    void theModuleNamesItselfAsAnImageProcessor() {
        assertThat(new MediaImageVipsProcessor().imageProcessor().name()).isEqualTo("vips");
    }

    @Test
    void theFeatureIsNamedAfterTheModule() {
        assertThat(new MediaImageVipsProcessor().feature().getName()).isEqualTo("quarkpress-media-image-vips");
    }

    @Test
    void theModuleNeedsJava25() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(25);
    }

    @Test
    void theProcessorIsRegisteredUnlessImageIoIsChosen() {
        for (Optional<String> choice : List.of(Optional.<String>empty(), Optional.of("vips"))) {
            List<AdditionalBeanBuildItem> beans = new ArrayList<>();
            List<ExcludedTypeBuildItem> excluded = new ArrayList<>();
            new MediaImageVipsProcessor().beans(choice(choice), beans::add, excluded::add);
            assertThat(excluded).isEmpty();
            assertThat(beans).singleElement().satisfies(bean -> {
                assertThat(bean.getBeanClasses()).containsExactly(VipsImageProcessor.class.getName());
                assertThat(bean.isRemovable()).isFalse();
            });
        }
        List<AdditionalBeanBuildItem> beans = new ArrayList<>();
        List<ExcludedTypeBuildItem> excluded = new ArrayList<>();
        new MediaImageVipsProcessor().beans(choice(Optional.of("imageio")), beans::add, excluded::add);
        assertThat(beans).isEmpty();
        // left out even where its class is discovered on its own (as in these tests, without the registration)
        assertThat(excluded)
                .extracting(ExcludedTypeBuildItem::getMatch)
                .containsExactly(VipsImageProcessor.class.getName());
    }

    @Test
    void theLimitDefaultsAreRegisteredForRunTime() {
        assertThat(new MediaImageVipsProcessor().configBuilder().getBuilderClassName())
                .isEqualTo(VipsConfigBuilder.class.getName());
    }

    private static MediaBuildTimeConfig choice(Optional<String> processor) {
        return new MediaBuildTimeConfig() {
            @Override
            public Optional<String> storage() {
                return Optional.empty();
            }

            @Override
            public Optional<String> imageProcessor() {
                return processor;
            }
        };
    }

    /** A build step without its annotation would compile, pass the tests above and never run in a build. */
    @Test
    void everyBuildStepMethodCarriesTheAnnotation() {
        List<String> steps = new ArrayList<>();
        for (Method method : MediaImageVipsProcessor.class.getDeclaredMethods()) {
            if (method.isSynthetic() || Modifier.isPrivate(method.getModifiers())) {
                continue;
            }
            assertThat(method.isAnnotationPresent(BuildStep.class))
                    .as("%s carries @BuildStep", method.getName())
                    .isTrue();
            steps.add(method.getName());
        }
        assertThat(steps).containsExactlyInAnyOrder("feature", "imageProcessor", "beans", "configBuilder");
    }
}
