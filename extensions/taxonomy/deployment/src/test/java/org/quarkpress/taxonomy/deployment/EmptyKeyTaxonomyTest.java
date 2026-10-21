package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.taxonomy.Taxonomy;

class EmptyKeyTaxonomyTest {

    @Taxonomy(key = "", objectTypes = "post")
    static class NoKey {}

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.buildOnly(EmptyKeyTaxonomyTest.class, NoKey.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("QuarkPress: @Taxonomy on ")
                    .contains("Taxonomy names must be between 1 and 32 characters in length."));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
