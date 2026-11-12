package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The synthetic theme's post formats: only WordPress's format slugs beyond {@code standard} are known, so an unknown
 * one fails the start, naming the key and the slug.
 */
class ThemeFormatsRefusedDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(ThemeFormatsRefusedDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.rest-wp-v2.theme.formats", "aside,nope")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkpress.rest-wp-v2.theme.formats")
                    .contains("nope"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}
