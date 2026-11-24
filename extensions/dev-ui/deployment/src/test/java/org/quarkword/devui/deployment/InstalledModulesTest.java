package org.quarkword.devui.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quarkword.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;
import org.quarkword.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkword.devui.deployment.InstalledModules.Artifact;
import org.quarkword.devui.deployment.InstalledModules.FeatureInfo;
import org.quarkword.devui.deployment.InstalledModules.InstalledModule;

class InstalledModulesTest {

    @Test
    void eachQuarkWordExtensionIsListedWithWhatItDeclares() {
        List<InstalledModule> modules = InstalledModules.collect(
                List.of(
                        new Artifact("org.quarkword", "quarkword-scheduling", "1.2.3", true),
                        new Artifact("org.quarkword", "quarkword-persistence", "1.2.3", true),
                        new Artifact("org.quarkword", "quarkword-test-gen", "1.2.3", false),
                        new Artifact("io.quarkus", "quarkus-arc", "3.40.1", true),
                        new Artifact("com.other", "quarkword-x", "1", true),
                        new Artifact("org.quarkword", "other-x", "1", true)),
                Set.of("org.quarkword.scheduling", "org.quarkword.persistence")::contains,
                List.of(
                        new SiteFeatureBuildItem("scheduling", "scheduling", true, "Publishes"),
                        new SiteFeatureBuildItem("purge", "scheduling", false, "Purges")),
                List.of(new MigrationLocationBuildItem("persistence", 1)),
                List.of(
                        new QuarkWordConfigKeysBuildItem("persistence", "quarkword", Set.of("quarkword.a")),
                        new QuarkWordConfigKeysBuildItem("persistence", "quarkword", Set.of("quarkword.b")),
                        new QuarkWordConfigKeysBuildItem(
                                "scheduling", "quarkword.scheduling", Set.of("quarkword.scheduling.enabled"))));
        assertThat(modules)
                .containsExactly(
                        new InstalledModule(
                                "quarkword-persistence",
                                "persistence",
                                "1.2.3",
                                "org.quarkword.persistence",
                                List.of(),
                                1,
                                List.of("quarkword")),
                        new InstalledModule(
                                "quarkword-scheduling",
                                "scheduling",
                                "1.2.3",
                                "org.quarkword.scheduling",
                                List.of(
                                        new FeatureInfo("purge", false, "Purges"),
                                        new FeatureInfo("scheduling", true, "Publishes")),
                                null,
                                List.of("quarkword.scheduling")));
        assertThat(modules).extracting(InstalledModule::artifactId).doesNotContain("quarkword-x", "other-x");
    }

    @Test
    void theDevUiModuleHasTheDevuiCapabilityAndAMissingCapabilityIsNull() {
        List<InstalledModule> modules = InstalledModules.collect(
                List.of(
                        new Artifact("org.quarkword", "quarkword-dev-ui", "1.2.3", true),
                        new Artifact("org.quarkword", "quarkword-core", "1.2.3", true)),
                Set.of("org.quarkword.devui")::contains,
                List.of(),
                List.of(),
                List.of());
        assertThat(modules)
                .extracting(InstalledModule::artifactId)
                .containsExactly("quarkword-core", "quarkword-dev-ui");
        assertThat(modules).extracting(InstalledModule::capability).containsExactly(null, "org.quarkword.devui");
    }
}
