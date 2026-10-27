package org.quarkpress.devui.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.devui.deployment.InstalledModules.Artifact;
import org.quarkpress.devui.deployment.InstalledModules.FeatureInfo;
import org.quarkpress.devui.deployment.InstalledModules.InstalledModule;

class InstalledModulesTest {

    @Test
    void eachQuarkPressExtensionIsListedWithWhatItDeclares() {
        List<InstalledModule> modules = InstalledModules.collect(
                List.of(
                        new Artifact("org.quarkpress", "quarkpress-scheduling", "1.2.3", true),
                        new Artifact("org.quarkpress", "quarkpress-persistence", "1.2.3", true),
                        new Artifact("org.quarkpress", "quarkpress-test-gen", "1.2.3", false),
                        new Artifact("io.quarkus", "quarkus-arc", "3.40.1", true),
                        new Artifact("com.other", "quarkpress-x", "1", true),
                        new Artifact("org.quarkpress", "other-x", "1", true)),
                Set.of("org.quarkpress.scheduling", "org.quarkpress.persistence")::contains,
                List.of(
                        new SiteFeatureBuildItem("scheduling", "scheduling", true, "Publishes"),
                        new SiteFeatureBuildItem("purge", "scheduling", false, "Purges")),
                List.of(new MigrationLocationBuildItem("persistence", 1)),
                List.of(
                        new QuarkPressConfigKeysBuildItem("persistence", "quarkpress", Set.of("quarkpress.a")),
                        new QuarkPressConfigKeysBuildItem("persistence", "quarkpress", Set.of("quarkpress.b")),
                        new QuarkPressConfigKeysBuildItem(
                                "scheduling", "quarkpress.scheduling", Set.of("quarkpress.scheduling.enabled"))));
        assertThat(modules)
                .containsExactly(
                        new InstalledModule(
                                "quarkpress-persistence",
                                "persistence",
                                "1.2.3",
                                "org.quarkpress.persistence",
                                List.of(),
                                1,
                                List.of("quarkpress")),
                        new InstalledModule(
                                "quarkpress-scheduling",
                                "scheduling",
                                "1.2.3",
                                "org.quarkpress.scheduling",
                                List.of(
                                        new FeatureInfo("purge", false, "Purges"),
                                        new FeatureInfo("scheduling", true, "Publishes")),
                                null,
                                List.of("quarkpress.scheduling")));
        assertThat(modules).extracting(InstalledModule::artifactId).doesNotContain("quarkpress-x", "other-x");
    }

    @Test
    void theDevUiModuleHasTheDevuiCapabilityAndAMissingCapabilityIsNull() {
        List<InstalledModule> modules = InstalledModules.collect(
                List.of(
                        new Artifact("org.quarkpress", "quarkpress-dev-ui", "1.2.3", true),
                        new Artifact("org.quarkpress", "quarkpress-core", "1.2.3", true)),
                Set.of("org.quarkpress.devui")::contains,
                List.of(),
                List.of(),
                List.of());
        assertThat(modules)
                .extracting(InstalledModule::artifactId)
                .containsExactly("quarkpress-core", "quarkpress-dev-ui");
        assertThat(modules).extracting(InstalledModule::capability).containsExactly(null, "org.quarkpress.devui");
    }
}
