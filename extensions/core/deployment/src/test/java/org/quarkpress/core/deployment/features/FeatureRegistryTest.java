package org.quarkpress.core.deployment.features;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.FeatureDescriptor;
import org.quarkpress.core.FeatureRegistry;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;

class FeatureRegistryTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(TestFeatures.class, FeatureRegistryTest.class))
            .addBuildChainCustomizer(chain -> TestFeatures.declare(
                    chain,
                    new SiteFeatureBuildItem("scheduling", "scheduling", true, "Scheduled publishing"),
                    new SiteFeatureBuildItem("revisions", "revisions", false, "Revisions and autosaves")));

    @Inject
    FeatureRegistry registry;

    @Test
    void recordsEveryDeclaredFeatureSortedByKey() {
        assertThat(registry.all())
                .containsExactly(
                        new FeatureDescriptor("revisions", "revisions", false, "Revisions and autosaves"),
                        new FeatureDescriptor("scheduling", "scheduling", true, "Scheduled publishing"));
    }
}
