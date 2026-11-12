package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The synthetic theme's {@code html5} list: {@code none} says the feature is not declared and stands alone, so
 * {@code none} beside another entry fails the start, naming the key.
 */
class ThemeHtml5RefusedDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(ThemeHtml5RefusedDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.rest-wp-v2.theme.html5", "none,caption")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkpress.rest-wp-v2.theme.html5")
                    .contains("none"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
