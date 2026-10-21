package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.taxonomy.Taxonomy;

class InvalidLabelTaxonomyTest {

    @Taxonomy(
            key = "genre",
            objectTypes = "post",
            labels = {"add_new_item"})
    static class NoEquals {}

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.buildOnly(InvalidLabelTaxonomyTest.class, NoEquals.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("QuarkPress: @Taxonomy on ")
                    .contains("a label of @Taxonomy genre must read key=value: 'add_new_item'"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
