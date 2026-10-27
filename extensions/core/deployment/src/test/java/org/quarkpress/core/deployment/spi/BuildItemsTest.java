package org.quarkpress.core.deployment.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BuildItemsTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void migrationBandsStartAtOne(int band) {
        assertThatThrownBy(() -> new MigrationLocationBuildItem("m", band))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("migration band must be at least 1: " + band);
    }

    @Test
    void aMigrationLocationNamesItsModule() {
        assertThatThrownBy(() -> new MigrationLocationBuildItem(null, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("module");
        MigrationLocationBuildItem item = new MigrationLocationBuildItem("m", 1);
        assertThat(item.module()).isEqualTo("m");
        assertThat(item.band()).isEqualTo(1);
    }

    @Test
    void aSiteFeatureNeedsAKeyAndAModule() {
        assertThatThrownBy(() -> new SiteFeatureBuildItem(null, "m", true, "d"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key");
        assertThatThrownBy(() -> new SiteFeatureBuildItem("k", null, true, "d"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("module");
        assertThat(new SiteFeatureBuildItem("k", "m", true, "d").key()).isEqualTo("k");
    }

    interface SampleConfig {
        String someKey();
    }

    @Test
    void configKeysNameTheirModuleAndPrefix() {
        QuarkPressConfigKeysBuildItem item =
                QuarkPressConfigKeysBuildItem.of("sample", "quarkpress.sample", SampleConfig.class);
        assertThat(item.module()).isEqualTo("sample");
        assertThat(item.prefix()).isEqualTo("quarkpress.sample");
        assertThat(item.keys()).containsExactly("quarkpress.sample.some-key");
    }

    @Test
    void configKeysNeedAModuleAndAQuarkPressPrefix() {
        assertThatThrownBy(() -> new QuarkPressConfigKeysBuildItem(" ", "quarkpress.x", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a configuration declaration names its module");
        assertThatThrownBy(() -> new QuarkPressConfigKeysBuildItem("x", "quarkpressx", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a quarkpress prefix: quarkpressx");
        assertThat(new QuarkPressConfigKeysBuildItem("persistence", "quarkpress", Set.of()).prefix())
                .isEqualTo("quarkpress");
    }

    @Test
    void configKeysLieUnderTheDeclaredPrefix() {
        assertThatThrownBy(() -> new QuarkPressConfigKeysBuildItem(
                        "revisions", "quarkpress.revisions", Set.of("quarkpress.scheduling.enabled")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a key under quarkpress.revisions: quarkpress.scheduling.enabled");
        assertThatThrownBy(() -> new QuarkPressConfigKeysBuildItem(
                        "revisions", "quarkpress.revisions", Set.of("quarkpress.revisions")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a key under quarkpress.revisions: quarkpress.revisions");
        assertThatThrownBy(() -> new QuarkPressConfigKeysBuildItem("persistence", "quarkpress", Set.of("other.key")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a key under quarkpress: other.key");
        assertThat(new QuarkPressConfigKeysBuildItem(
                                "revisions", "quarkpress.revisions", Set.of("quarkpress.revisions.keep"))
                        .keys())
                .containsExactly("quarkpress.revisions.keep");
    }
}
