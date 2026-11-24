package org.quarkword.persistence.deployment.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.elsewhere.NetworkBinder;
import org.quarkword.persistence.deployment.DbApp;
import org.quarkword.persistence.deployment.Failures;

class NetworkBindingTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.buildOnly(NetworkBinder.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains(NetworkBinder.class.getName())
                    .contains("calls SiteBinding.bind outside QuarkWord core and persistence"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
