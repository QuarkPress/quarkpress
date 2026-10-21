package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.taxonomy.Taxonomy;

class DuplicateTaxonomyTest {

    @Taxonomy(key = "category", objectTypes = "page")
    static class AnotherCategory {}

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.buildOnly(DuplicateTaxonomyTest.class, AnotherCategory.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("java.lang.IllegalStateException: "
                            + "QuarkPress: the taxonomy category is registered more than once"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
