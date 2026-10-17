package org.quarkpress.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.elsewhere.SiteBinder;
import org.quarkpress.persistence.deployment.DbApp;
import org.quarkpress.persistence.deployment.Failures;

class SiteBindTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnly(SiteBinder.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains(SiteBinder.class.getName())
                    .contains("calls SiteBinding.bind outside QuarkPress core and persistence"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
