package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.SystemIdentity;

class SystemIdentityMultisiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.multisite(SystemIdentityMultisiteDbTest.class);

    @Inject
    SystemIdentity system;

    @Inject
    Capabilities capabilities;

    @Test
    void inANetworkTheSystemPassesTheNetworkRestrictionsButNotUnfilteredHtml() throws Exception {
        assertThat(system.call(() -> capabilities.can("create_users"))).isTrue();
        assertThat(system.call(() -> capabilities.can("update_core"))).isTrue();
        assertThat(system.call(() -> capabilities.can("delete_site"))).isTrue();
        assertThat(system.call(() -> capabilities.can("manage_network"))).isTrue();
        assertThat(system.call(() -> capabilities.can("unfiltered_html"))).isFalse();
    }
}
