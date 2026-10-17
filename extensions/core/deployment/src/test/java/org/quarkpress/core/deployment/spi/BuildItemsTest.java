package org.quarkpress.core.deployment.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
