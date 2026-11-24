package org.quarkword.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class MissingDatasourceTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Failures.class))
            .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.http.test-ssl-port", "0")
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("QuarkWord needs its own datasource named \"quarkword\"")
                    .contains("quarkus.datasource.\"quarkword\".db-kind"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
