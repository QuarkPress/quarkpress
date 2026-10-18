package org.quarkpress.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class MissingMainSiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(MissingMainSiteDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.main-site-id", "8000000000000000000")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkpress.main-site-id is 8000000000000000000")
                    .contains("the main site's domain and path are the network's address"));

    @Test
    void startupFails() {
        // assertException above checks the failure
    }
}
