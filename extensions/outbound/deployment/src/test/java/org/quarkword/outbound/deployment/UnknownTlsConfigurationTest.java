package org.quarkword.outbound.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A TLS configuration name that the registry does not know stops the application at start, not at the first fetch. */
class UnknownTlsConfigurationTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withEmptyApplication()
            .overrideRuntimeConfigKey("quarkword.outbound.tls-configuration-name", "missing")
            .assertException(failure -> assertThat(messages(failure))
                    .contains("quarkword.outbound.tls-configuration-name names no TLS configuration: missing"));

    @Test
    void theStartFails() {
        // assertException above checks the failure
    }

    private static List<String> messages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.add(t.getMessage());
        }
        return messages;
    }
}
