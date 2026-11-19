package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.CapabilityDeniedException;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.ImportedUser;
import org.quarkpress.security.SystemIdentity;
import org.quarkpress.security.UserImports;

/**
 * Importing users in a network: as WordPress maps {@code create_users} there, a site administrator may not import (the
 * network option {@code add_new_users} is off by default), a network administrator and the system identity may.
 */
class UserImportsMultisiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.multisite(
            UserImportsMultisiteDbTest.class,
            ImportUsers.class,
            RecordingProvisioner.class,
            ImportEvents.class,
            TestData.class,
            Identities.class);

    @Inject
    UserImports imports;

    @Inject
    RecordingProvisioner provisioner;

    @Inject
    SystemIdentity system;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Test
    void aSiteAdministratorIsRefusedBeforeTheIdentityProviderIsCalled() {
        UserId admin = TestData.profile(dataSource, "site-admin");
        TestData.member(dataSource, SiteId.of(1), admin, "[\"administrator\"]", "{}");
        SecurityIdentity identity = Identities.user(admin, TestData.subject(dataSource, admin));
        String name = ImportUsers.word();

        assertThatThrownBy(() -> Identities.as(identity, () -> imports.importUser(ImportUsers.john(name))))
                .isInstanceOfSatisfying(CapabilityDeniedException.class, denied -> {
                    assertThat(denied.capability()).isEqualTo("create_users");
                    assertThat(denied.anonymous()).isFalse();
                });
        assertThat(provisioner.callsFor(name)).isEmpty();
    }

    @Test
    void aNetworkAdministratorImports() {
        UserId admin = TestData.profile(dataSource, "network-admin");
        TestData.networkAdmin(dataSource, admin);
        SecurityIdentity identity = Identities.user(admin, TestData.subject(dataSource, admin));
        String name = ImportUsers.word();

        ImportedUser imported = Identities.as(identity, () -> imports.importUser(ImportUsers.john(name)));

        assertThat(imported.profileCreated()).isTrue();
        assertThat(imported.profile().nicename()).isEqualTo(name);
        assertThat(provisioner.callsFor(name)).hasSize(1);
    }

    @Test
    void theSystemImports() throws Exception {
        String name = ImportUsers.word();
        ImportedUser imported = system.call(() -> imports.importUser(ImportUsers.john(name)));
        assertThat(imported.profileCreated()).isTrue();
    }
}
