package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.CapabilityCheck;
import org.quarkpress.core.CapabilityDeniedException;
import org.quarkpress.core.NetworkCapabilities;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.SystemIdentity;
import org.quarkpress.security.runtime.CapabilityGuard;
import org.quarkpress.security.runtime.SecurityCapabilityCheck;

/** In a network: the network services' check and the service guard. */
class CapabilityCheckDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            SecurityApp.multisite(CapabilityCheckDbTest.class, TestData.class, Identities.class);

    static final SiteId MAIN = SiteId.of(1);

    @Inject
    CapabilityCheck check;

    @Inject
    CapabilityGuard guard;

    @Inject
    SystemIdentity system;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private SecurityIdentity identity(UserId user) {
        return Identities.user(user, TestData.subject(dataSource, user));
    }

    @Test
    void theSecurityModuleReplacesTheOpenCheck() {
        assertThat(ClientProxy.unwrap(check)).isInstanceOf(SecurityCapabilityCheck.class);
    }

    @Test
    void networkAdministratorsAndTheSystemMayCreateSitesAndSiteAdministratorsMayNot() throws Exception {
        UserId networkAdmin = TestData.profile(dataSource, "network-admin");
        TestData.networkAdmin(dataSource, networkAdmin);
        UserId siteAdmin = TestData.profile(dataSource, "site-admin");
        TestData.member(dataSource, MAIN, siteAdmin, "[\"administrator\"]", "{}");
        assertThatCode(() -> Identities.as(identity(networkAdmin), () -> {
                    check.requireNetwork(NetworkCapabilities.CREATE_SITES);
                    return null;
                }))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> Identities.as(identity(siteAdmin), () -> {
                    check.requireNetwork(NetworkCapabilities.CREATE_SITES);
                    return null;
                }))
                .isInstanceOf(CapabilityDeniedException.class)
                .hasMessage("the caller lacks the network capability create_sites")
                .extracting(denied -> ((CapabilityDeniedException) denied).anonymous())
                .isEqualTo(false);
        SecurityIdentity anonymous = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(""))
                .setAnonymous(true)
                .build();
        assertThatThrownBy(() -> Identities.as(anonymous, () -> {
                    check.requireNetwork(NetworkCapabilities.DELETE_SITES);
                    return null;
                }))
                .isInstanceOf(CapabilityDeniedException.class)
                .extracting(denied -> ((CapabilityDeniedException) denied).anonymous())
                .isEqualTo(true);
        // outside a request nobody is authenticated
        assertThatThrownBy(() -> check.requireNetwork(NetworkCapabilities.DELETE_SITES))
                .isInstanceOf(CapabilityDeniedException.class)
                .extracting(denied -> ((CapabilityDeniedException) denied).anonymous())
                .isEqualTo(true);
        system.run(() -> check.requireNetwork(NetworkCapabilities.MANAGE_SITES));
    }

    @Test
    void theGuardChecksTheTargetSite() {
        SiteId other = TestData.site(dataSource);
        UserId admin = TestData.profile(dataSource, "admin");
        TestData.member(dataSource, MAIN, admin, "[\"administrator\"]", "{}");
        assertThatCode(() -> Identities.as(identity(admin), () -> {
                    guard.require(MAIN, "manage_options", java.util.Optional.empty());
                    guard.requireHere("manage_options");
                    return null;
                }))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> Identities.as(identity(admin), () -> {
                    guard.require(other, "manage_options", java.util.Optional.empty());
                    return null;
                }))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability manage_options on site " + other.value());
        SecurityIdentity anonymous = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(""))
                .setAnonymous(true)
                .build();
        assertThatThrownBy(() -> Identities.as(anonymous, () -> {
                    guard.requireHere("read");
                    return null;
                }))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("the caller lacks the capability read for this request");
    }

    @Test
    void anAuthenticatedCallerWithoutAQuarkPressPrincipalIsForbiddenNotChallenged() {
        SecurityIdentity basic = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("mtls-client"))
                .build();
        assertThatThrownBy(() -> Identities.as(basic, () -> {
                    guard.requireHere("manage_options");
                    return null;
                }))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability manage_options for this request");
        assertThatThrownBy(() -> Identities.as(basic, () -> {
                    guard.require(MAIN, "read", java.util.Optional.empty());
                    return null;
                }))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability read on site 1");
    }
}
