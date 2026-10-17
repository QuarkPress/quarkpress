package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Without the multisite module the main site must exist, or the application does not start. */
class MissingMainSiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(MissingMainSiteDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.main-site-id", "8000000000000000000")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkpress.main-site-id is 8000000000000000000")
                    .contains("no site with that id"));

    @Test
    void startupFails() {
        // assertException above checks the failure
    }
}
