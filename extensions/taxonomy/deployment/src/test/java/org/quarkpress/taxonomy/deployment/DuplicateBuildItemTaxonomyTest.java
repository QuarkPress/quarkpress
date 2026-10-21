package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.test.QuarkusExtensionTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.deployment.spi.TaxonomyBuildItem;
import org.quarkpress.taxonomy.Taxonomy;

class DuplicateBuildItemTaxonomyTest {

    @Taxonomy(key = "series", objectTypes = "post")
    static class Series {}

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.buildOnly(DuplicateBuildItemTaxonomyTest.class, Series.class)
            .addBuildChainCustomizer(chain -> chain.addBuildStep(new BuildStep() {
                        @Override
                        public void execute(BuildContext context) {
                            context.produce(new TaxonomyBuildItem(TaxonomyDefinition.builder("series", List.of("page"))
                                    .build()));
                        }
                    })
                    .produces(TaxonomyBuildItem.class)
                    .build())
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("java.lang.IllegalStateException: "
                            + "QuarkPress: the taxonomy series is registered more than once"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
