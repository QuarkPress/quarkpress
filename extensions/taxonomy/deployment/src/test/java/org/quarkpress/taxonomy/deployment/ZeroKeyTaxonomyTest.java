package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.taxonomy.Taxonomy;

/** WordPress refuses the key "0" as empty. */
class ZeroKeyTaxonomyTest {

    @Taxonomy(key = "0", objectTypes = "post")
    static class ZeroKey {}

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.buildOnly(ZeroKeyTaxonomyTest.class, ZeroKey.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("QuarkPress: @Taxonomy on ")
                    .contains("Taxonomy names must be between 1 and 32 characters in length."));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
