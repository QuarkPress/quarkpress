package org.quarkpress.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.deployment.annotations.BuildStep;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

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
        assertThat(steps).containsExactlyInAnyOrder("feature", "imageProcessor");
    }
}
