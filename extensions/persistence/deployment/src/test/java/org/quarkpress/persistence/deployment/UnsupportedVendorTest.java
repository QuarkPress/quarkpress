package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class UnsupportedVendorTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Failures.class))
            .overrideConfigKey("quarkus.datasource.\"quarkpress\".db-kind", "mariadb")
            .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.http.test-ssl-port", "0")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("io.quarkus.runtime.configuration.ConfigurationException")
                    .contains("MariaDB uses the MySQL migrations of QuarkPress")
                    .contains("quarkpress.persistence.migration-vendor=mysql"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
