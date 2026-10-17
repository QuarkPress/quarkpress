package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;

class MigrationBandsTest {

    @Test
    void distinctBandsAreFine() {
        assertThat(MigrationBands.problems(List.of(
                        new MigrationLocationBuildItem("persistence", 1),
                        new MigrationLocationBuildItem("multisite", 2))))
                .isEmpty();
    }

    @Test
    void twoModulesInOneBandAreReported() {
        assertThat(MigrationBands.problems(List.of(
                        new MigrationLocationBuildItem("persistence", 1),
                        new MigrationLocationBuildItem("intruder", 1))))
                .singleElement()
                .satisfies(problem -> assertThat(problem)
                        .contains("V1_")
                        .contains("persistence")
                        .contains("intruder"));
    }

    @Test
    void oneModuleInTwoBandsIsReported() {
        assertThat(MigrationBands.problems(List.of(
                        new MigrationLocationBuildItem("posts", 4), new MigrationLocationBuildItem("posts", 5))))
                .singleElement()
                .satisfies(problem ->
                        assertThat(problem).contains("posts").contains("4").contains("5"));
    }
}
