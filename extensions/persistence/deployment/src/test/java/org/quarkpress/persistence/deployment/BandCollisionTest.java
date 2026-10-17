package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;

class BandCollisionTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnly()
            .addBuildChainCustomizer(chain -> chain.addBuildStep(
                            context -> context.produce(new MigrationLocationBuildItem("intruder", 1)))
                    .produces(MigrationLocationBuildItem.class)
                    .build())
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("band V1_")
                    .contains("persistence")
                    .contains("intruder"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
