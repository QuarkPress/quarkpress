package org.quarkword.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.test.QuarkusExtensionTest;
import java.util.List;
import java.util.TreeSet;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The vips module installs its feature next to the media module. A build step added to the chain records the build's
 * features as a configuration default the test reads back.
 */
class VipsSkeletonDbTest {

    private static final String FEATURES = "vips-skeleton-probe.features";

    @RegisterExtension
    static final QuarkusExtensionTest app =
            VipsApp.with(VipsSkeletonDbTest.class).addBuildChainCustomizer(VipsSkeletonDbTest::probe);

    static void probe(BuildChainBuilder builder) {
        builder.addBuildStep(context -> {
                    TreeSet<String> names = new TreeSet<>();
                    for (FeatureBuildItem feature : context.consumeMulti(FeatureBuildItem.class)) {
                        names.add(feature.getName());
                    }
                    context.produce(new RunTimeConfigurationDefaultBuildItem(FEATURES, String.join(",", names)));
                })
                .consumes(FeatureBuildItem.class)
                .produces(RunTimeConfigurationDefaultBuildItem.class)
                .build();
    }

    @Test
    void theFeatureIsInstalled() {
        List<String> features = ConfigProvider.getConfig().getValues(FEATURES, String.class);
        assertThat(features).contains("quarkword-media-image-vips", "quarkword-media");
    }
}
