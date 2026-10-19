package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.Capabilities;

/** Checks made in a network for a request that resolved to no site: only exist, plus the bypass and the system. */
class CapabilitiesUnresolvedDbTest {

    /**
     * The multisite build without the multisite module: no resolver answers (the single-site resolver is off), so every
     * request is unresolved, as a request for an unknown host is in a network.
     */
    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(
                    CapabilitiesUnresolvedDbTest.class, TestData.class, Identities.class)
            .overrideConfigKey("quarkpress.multisite.enabled", "true");

    static final SiteId MAIN = SiteId.of(1);

    @Inject
    Capabilities capabilities;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private SecurityIdentity identity(UserId user) {
        return Identities.user(user, TestData.subject(dataSource, user));
    }

    @Test
    void aMainSiteEditorHoldsOnlyExist() {
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, MAIN, editor, "[\"editor\"]", "{}");
        SecurityIdentity identity = identity(editor);
        assertThat(Identities.as(identity, () -> capabilities.can("edit_posts")))
                .isFalse();
        assertThat(Identities.as(identity, () -> capabilities.can("read"))).isFalse();
        assertThat(Identities.as(identity, () -> capabilities.can("exist"))).isTrue();
        // the same user on the main site, named explicitly
        assertThat(capabilities.userCan(editor, MAIN, "edit_posts", Optional.empty()))
                .isTrue();
    }

    @Test
    void aNetworkAdministratorAndTheSystemStillPass() {
        UserId admin = TestData.profile(dataSource, "network-admin");
        TestData.networkAdmin(dataSource, admin);
        assertThat(Identities.as(identity(admin), () -> capabilities.can("manage_sites")))
                .isTrue();
        assertThat(Identities.as(Identities.system(), () -> capabilities.can("manage_sites")))
                .isTrue();
        assertThat(Identities.as(Identities.system(), () -> capabilities.can("unfiltered_html")))
                .isFalse();
    }
}
