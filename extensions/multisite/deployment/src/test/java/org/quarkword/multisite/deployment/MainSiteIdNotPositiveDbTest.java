package org.quarkword.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A main site id of 0 or less fails the start. */
class MainSiteIdNotPositiveDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(MainSiteIdNotPositiveDbTest.class)
            .overrideRuntimeConfigKey("quarkword.main-site-id", "0")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("quarkword.main-site-id is 0; it must be the positive id of a site in qw_site"));

    @Test
    void startupFails() {
        // assertException above checks the failure
    }
}
