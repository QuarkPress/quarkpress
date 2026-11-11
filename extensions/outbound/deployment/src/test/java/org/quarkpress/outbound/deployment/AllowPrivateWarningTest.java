package org.quarkpress.outbound.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.outbound.runtime.OutboundStartup;

/** Lifting the address rule is announced once, at start-up. */
class AllowPrivateWarningTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withEmptyApplication()
            .overrideRuntimeConfigKey("quarkpress.outbound.allow-private-addresses", "true")
            .setLogRecordPredicate(record -> record.getLoggerName().equals(OutboundStartup.class.getName()))
            .assertLogRecords(records -> assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.getLevel().intValue()).isEqualTo(Level.WARNING.intValue());
                assertThat(record.getMessage())
                        .isEqualTo("quarkpress.outbound.allow-private-addresses is true: outbound requests"
                                + " may reach loopback and private addresses; use it for tests only");
            }));

    @Test
    void theWarningIsLoggedOnce() {
        // assertLogRecords above checks the log
    }
}
