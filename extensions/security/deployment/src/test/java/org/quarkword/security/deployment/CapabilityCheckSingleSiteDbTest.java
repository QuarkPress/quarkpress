package org.quarkword.security.deployment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.CapabilityCheck;
import org.quarkword.core.CapabilityDeniedException;
import org.quarkword.core.NetworkCapabilities;
import org.quarkword.core.SiteId;
import org.quarkword.core.UserId;

/** On a single site nobody holds network capabilities through a role or a network administrator row. */
class CapabilityCheckSingleSiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            SecurityApp.with(CapabilityCheckSingleSiteDbTest.class, TestData.class, Identities.class);

    @Inject
    CapabilityCheck check;

    @Inject
    @DataSource("quarkword")
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
