package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.CapabilityCheck;
import org.quarkpress.core.CapabilityDeniedException;
import org.quarkpress.core.NetworkCapabilities;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/** On a single site nobody holds network capabilities through a role or a network administrator row. */
class CapabilityCheckSingleSiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            SecurityApp.with(CapabilityCheckSingleSiteDbTest.class, TestData.class, Identities.class);

    @Inject
    CapabilityCheck check;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Test
    void administratorsAndNetworkAdminRowsGetNoNetworkCapability() {
        UserId admin = TestData.profile(dataSource, "admin");
        TestData.member(dataSource, SiteId.of(1), admin, "[\"administrator\"]", "{}");
        TestData.networkAdmin(dataSource, admin);
        assertThatThrownBy(() -> Identities.as(Identities.user(admin, TestData.subject(dataSource, admin)), () -> {
                    check.requireNetwork(NetworkCapabilities.CREATE_SITES);
                    return null;
                }))
                .isInstanceOf(CapabilityDeniedException.class)
                .hasMessage("the caller lacks the network capability create_sites");
    }
}
