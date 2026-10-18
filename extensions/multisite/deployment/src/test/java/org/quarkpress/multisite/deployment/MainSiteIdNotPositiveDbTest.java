package org.quarkpress.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A main site id of 0 or less fails the start. */
class MainSiteIdNotPositiveDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(MainSiteIdNotPositiveDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.main-site-id", "0")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkpress.main-site-id is 0; it must be the positive id of a site in qp_site"));

    @Test
    void startupFails() {
        // assertException above checks the failure
    }
}
