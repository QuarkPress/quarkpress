package org.quarkword.core.deployment.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        QuarkWordConfigKeysBuildItem item =
                QuarkWordConfigKeysBuildItem.of("sample", "quarkword.sample", SampleConfig.class);
        assertThat(item.module()).isEqualTo("sample");
        assertThat(item.prefix()).isEqualTo("quarkword.sample");
        assertThat(item.keys()).containsExactly("quarkword.sample.some-key");
    }

    @Test
    void configKeysNeedAModuleAndAQuarkWordPrefix() {
        assertThatThrownBy(() -> new QuarkWordConfigKeysBuildItem(" ", "quarkword.x", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a configuration declaration names its module");
        assertThatThrownBy(() -> new QuarkWordConfigKeysBuildItem("x", "quarkwordx", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a quarkword prefix: quarkwordx");
        assertThat(new QuarkWordConfigKeysBuildItem("persistence", "quarkword", Set.of()).prefix())
                .isEqualTo("quarkword");
    }

    @Test
    void configKeysLieUnderTheDeclaredPrefix() {
        assertThatThrownBy(() -> new QuarkWordConfigKeysBuildItem(
                        "revisions", "quarkword.revisions", Set.of("quarkword.scheduling.enabled")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a key under quarkword.revisions: quarkword.scheduling.enabled");
        assertThatThrownBy(() -> new QuarkWordConfigKeysBuildItem(
                        "revisions", "quarkword.revisions", Set.of("quarkword.revisions")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a key under quarkword.revisions: quarkword.revisions");
        assertThatThrownBy(() -> new QuarkWordConfigKeysBuildItem("persistence", "quarkword", Set.of("other.key")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a key under quarkword: other.key");
        assertThat(new QuarkWordConfigKeysBuildItem(
                                "revisions", "quarkword.revisions", Set.of("quarkword.revisions.keep"))
                        .keys())
                .containsExactly("quarkword.revisions.keep");
    }

    interface MapConfig {
        Map<String, String> m();

        Optional<Map<String, Integer>> optionalMap();

        List<String> list();
    }

    @Test
    void aMapMethodIsDeclaredAsOneFurtherSegment() {
        assertThat(QuarkWordConfigKeysBuildItem.of("x", "quarkword.x", MapConfig.class)
                        .keys())
                .containsExactlyInAnyOrder("quarkword.x.m.*", "quarkword.x.optional-map.*", "quarkword.x.list[]");
    }

    interface Group {
        String value();
    }

    interface GroupMapConfig {
        Map<String, Group> groups();
    }

    @Test
    void aMapOfGroupsIsStillDeclaredByHand() {
        assertThatThrownBy(() -> QuarkWordConfigKeysBuildItem.of("x", "quarkword.x", GroupMapConfig.class))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("groups is a map of configuration groups");
    }
}
