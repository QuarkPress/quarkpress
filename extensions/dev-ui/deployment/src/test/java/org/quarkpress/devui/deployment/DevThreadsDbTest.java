package org.quarkpress.devui.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.devui.runtime.DevThreads;

/** A Dev UI call hops to a detached thread, which an application with transactions refuses inside one. */
class DevThreadsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DevUiApp.nonDevelopment(DevThreadsDbTest.class);

    @Test
    void aCallInsideATransactionIsRefused() {
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().call(() -> DevThreads.detached(() -> "never")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a Dev UI call is not made inside a transaction");
    }

    @Test
    void aCallOutsideATransactionRuns() {
        assertThat(DevThreads.detached(() -> "ran")).isEqualTo("ran");
    }
}
