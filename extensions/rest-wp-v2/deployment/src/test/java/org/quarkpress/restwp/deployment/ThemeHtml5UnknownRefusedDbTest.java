package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The synthetic theme's {@code html5} list: only WordPress's seven markup kinds (or {@code none}) are known, so an
 * unknown value fails the start, naming the key and the value.
 */
class ThemeHtml5UnknownRefusedDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(ThemeHtml5UnknownRefusedDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.rest-wp-v2.theme.html5", "caption,nope")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkpress.rest-wp-v2.theme.html5")
                    .contains("nope"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
