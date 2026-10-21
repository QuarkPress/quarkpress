package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.deployment.spi.TaxonomyBuildItem;
import org.quarkpress.security.TaxonomyCapabilities;
import org.quarkpress.taxonomy.Taxonomies;

class RegisteredByBuildItemsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.with(RegisteredByBuildItemsDbTest.class)
            .addBuildChainCustomizer(chain -> chain.addBuildStep(new BuildStep() {
                        @Override
                        public void execute(BuildContext context) {
                            context.produce(new TaxonomyBuildItem(TaxonomyDefinition.builder("series", List.of("post"))
                                    .hierarchical(true)
                                    .capabilities(Map.of("edit_terms", "edit_series"))
                                    .build()));
                        }
                    })
                    .produces(TaxonomyBuildItem.class)
                    .build());

    @Inject
    Taxonomies taxonomies;

    @Test
    void buildItemsRegisterTaxonomies() {
        assertThat(taxonomies.hierarchical("series")).isTrue();
        assertThat(taxonomies.capabilities("series"))
                .contains(TaxonomyCapabilities.of("series", Map.of("edit_terms", "edit_series")));
    }
}
