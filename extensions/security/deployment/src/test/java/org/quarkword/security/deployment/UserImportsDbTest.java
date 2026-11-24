package org.quarkword.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.CapabilityDeniedException;
import org.quarkword.core.SiteId;
import org.quarkword.core.UserId;
import org.quarkword.security.ImportedUser;
import org.quarkword.security.NewUser;
import org.quarkword.security.ProfileClaims;
import org.quarkword.security.SystemIdentity;
import org.quarkword.security.UserImports;
import org.quarkword.security.UserProfile;
import org.quarkword.security.UserProfileProvisioner;
import org.quarkword.security.UserProfiles;
import org.quarkword.security.UserProvisioningException;

/**
 * Importing users on a single site: the {@code create_users} check before the identity provider is called, the
 * profile named as WordPress names an imported user, idempotence, and failures that leave no profile.
 */
class UserImportsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(
            UserImportsDbTest.class,
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
    ImportEvents events;

    @Inject
    SystemIdentity system;

    @Inject
    UserProfiles profiles;

    @Inject
    UserProfileProvisioner logins;

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @AfterEach
    void healthy() {
        provisioner.stopFailing();
    }

    private ImportedUser asSystem(NewUser user) throws Exception {
        return system.call(() -> imports.importUser(user));
    }

    private static SecurityIdentity anonymous() {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(""))
                .setAnonymous(true)
                .build();
    }

    private SecurityIdentity member(String roles) {
        UserId user = TestData.profile(dataSource, "member");
        TestData.member(dataSource, SiteId.of(1), user, roles, "{}");
        return Identities.user(user, TestData.subject(dataSource, user));
    }

    @Test
    void theSystemImportsAUserAndItsProfileIsNamedAsWordPressNamesIt() throws Exception {
        String name = ImportUsers.word();
        ImportedUser imported = asSystem(ImportUsers.john(name));

        assertThat(imported.createdInIdentityProvider()).isTrue();
        assertThat(imported.profileCreated()).isTrue();
        UserProfile profile = imported.profile();
        assertThat(profile.subject()).isEqualTo(RecordingProvisioner.subjectOf(name));
        assertThat(profile.nicename()).isEqualTo(name);
        assertThat(profile.displayName()).isEqualTo("John Doe");
        assertThat(profile.email()).contains(name + "@example.com");
        assertThat(profile.lastLoginAt()).isEmpty();
        assertThat(profiles.findBySubject(profile.subject())).contains(profile);
        assertThat(events.forSubject(profile.subject())).hasSize(1);
        assertThat(events.forSubject(profile.subject()).get(0).profile()).isEqualTo(profile);

        assertThat(provisioner.callsFor(name)).hasSize(1);
        NewUser received = provisioner.callsFor(name).get(0).user();
        assertThat(received.password()).isPresent();
        assertThat(received.password().get().value()).isEqualTo(ImportUsers.PHPASS);
        assertThat(received.password().get().algorithm()).isEqualTo("wordpress");
        assertThat(received.firstName()).contains("John");
        assertThat(received.lastName()).contains("Doe");
    }

    @Test
    void importingTheSameUserAgainReturnsWhatExistsUnchanged() throws Exception {
        String name = ImportUsers.word();
        ImportedUser first = asSystem(ImportUsers.john(name));
        ImportedUser again = asSystem(new NewUser(
                name,
                Optional.of("other-" + name + "@example.com"),
                "Somebody Else",
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));

        assertThat(again.createdInIdentityProvider()).isFalse();
        assertThat(again.profileCreated()).isFalse();
        assertThat(again.profile()).isEqualTo(first.profile());
        assertThat(events.forSubject(first.profile().subject())).hasSize(1);
    }

    @Test
    void aSubjectThatAlreadyHasAProfileKeepsIt() throws Exception {
        String name = ImportUsers.word();
        String subject = "login-" + UUID.randomUUID();
        UserProfile loggedIn =
                logins.provision(new ProfileClaims(subject, Optional.of(name), Optional.of("Jo"), Optional.empty()));
        provisioner.subjectFor(name, subject);

        ImportedUser imported = asSystem(ImportUsers.john(name));

        assertThat(imported.createdInIdentityProvider()).isTrue();
        assertThat(imported.profileCreated()).isFalse();
        assertThat(imported.profile()).isEqualTo(loggedIn);
        assertThat(imported.profile().displayName()).isEqualTo("Jo");
    }

    @Test
    void anAnonymousCallerIsRefusedBeforeTheIdentityProviderIsCalled() {
        String name = ImportUsers.word();
        assertThatThrownBy(() -> Identities.as(anonymous(), () -> imports.importUser(ImportUsers.john(name))))
                .isInstanceOfSatisfying(CapabilityDeniedException.class, denied -> {
                    assertThat(denied.capability()).isEqualTo("create_users");
                    assertThat(denied.anonymous()).isTrue();
                });
        assertThat(provisioner.callsFor(name)).isEmpty();
        assertThat(profiles.findBySubject(RecordingProvisioner.subjectOf(name))).isEmpty();
    }

    @Test
    void outsideARequestNobodyIsAuthenticatedAndTheImportIsRefused() {
        String name = ImportUsers.word();
        assertThatThrownBy(() -> imports.importUser(ImportUsers.john(name)))
                .isInstanceOf(CapabilityDeniedException.class);
        assertThat(provisioner.callsFor(name)).isEmpty();
    }

    @Test
    void anEditorIsRefusedAndAnAdministratorOfTheSingleSiteImports() {
        String refused = ImportUsers.word();
        SecurityIdentity editor = member("[\"editor\"]");
        assertThatThrownBy(() -> Identities.as(editor, () -> imports.importUser(ImportUsers.john(refused))))
                .isInstanceOfSatisfying(CapabilityDeniedException.class, denied -> {
                    assertThat(denied.capability()).isEqualTo("create_users");
                    assertThat(denied.anonymous()).isFalse();
                });
        assertThat(provisioner.callsFor(refused)).isEmpty();

        String allowed = ImportUsers.word();
        SecurityIdentity administrator = member("[\"administrator\"]");
        ImportedUser imported = Identities.as(administrator, () -> imports.importUser(ImportUsers.john(allowed)));
        assertThat(imported.profileCreated()).isTrue();
        assertThat(imported.profile().nicename()).isEqualTo(allowed);
    }

    @Test
    void aProvisionerFailurePropagatesAndLeavesNoProfile() {
        String name = ImportUsers.word();
        UserProvisioningException failure =
                new UserProvisioningException(UserProvisioningException.Reason.UNAVAILABLE, "down");
        provisioner.failWith(() -> failure);

        assertThatThrownBy(() -> asSystem(ImportUsers.john(name))).isSameAs(failure);
        assertThat(provisioner.callsFor(name)).hasSize(1);
        assertThat(profiles.findBySubject(RecordingProvisioner.subjectOf(name))).isEmpty();
        assertThat(events.forSubject(RecordingProvisioner.subjectOf(name))).isEmpty();

        provisioner.stopFailing();
        ImportedUser retried = asSystemUnchecked(ImportUsers.john(name));
        assertThat(retried.profileCreated()).isTrue();
    }

    private ImportedUser asSystemUnchecked(NewUser user) {
        try {
            return asSystem(user);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aTakenNicenameGetsWordPresssSuffix() throws Exception {
        String name = ImportUsers.word();
        asSystem(ImportUsers.john(name));
        // another login whose nicename base is the same: nicenames are lower-cased
        ImportedUser second = asSystem(ImportUsers.john(name.toUpperCase(java.util.Locale.ROOT)));

        assertThat(second.profileCreated()).isTrue();
        assertThat(second.profile().nicename()).isEqualTo(name + "-2");
    }

    @Test
    void aLaterLoginKeepsTheImportedNames() throws Exception {
        String name = ImportUsers.word();
        ImportedUser imported = asSystem(ImportUsers.john(name));
        String subject = imported.profile().subject();

        UserProfile afterLogin = logins.provision(
                new ProfileClaims(subject, Optional.of("renamed-" + name), Optional.of("Other"), Optional.of("Name")));

        assertThat(afterLogin.id()).isEqualTo(imported.profile().id());
        assertThat(afterLogin.displayName()).isEqualTo("John Doe");
        assertThat(afterLogin.nicename()).isEqualTo(name);
        assertThat(afterLogin.lastLoginAt()).isPresent();
    }

    @Test
    void anEmptyDisplayNameIsDerivedAsWordPressDerivesIt() throws Exception {
        String named = ImportUsers.word();
        ImportedUser withNames = asSystem(
                new NewUser(named, Optional.empty(), "", Optional.of("Jane"), Optional.of("Roe"), Optional.empty()));
        assertThat(withNames.profile().displayName()).isEqualTo("Jane Roe");

        String first = ImportUsers.word();
        ImportedUser withFirst = asSystem(
                new NewUser(first, Optional.empty(), "0", Optional.of("Jane"), Optional.empty(), Optional.empty()));
        assertThat(withFirst.profile().displayName()).isEqualTo("Jane");

        String bare = ImportUsers.word();
        ImportedUser withoutNames = asSystem(NewUser.of(bare, " "));
        assertThat(withoutNames.profile().displayName()).isEqualTo(bare);
        assertThat(withoutNames.profile().email()).isEmpty();
    }

    @Test
    void theIdentityProviderIsCalledOutsideTheCallersTransaction() throws Exception {
        String name = ImportUsers.word();
        ImportedUser imported = system.call(
                () -> QuarkusTransaction.requiringNew().call(() -> imports.importUser(ImportUsers.john(name))));

        assertThat(provisioner.callsFor(name))
                .singleElement()
                .satisfies(call -> assertThat(call.inTransaction()).isFalse());
        assertThat(imported.profileCreated()).isTrue();
    }

    @Test
    void aUsernameWithoutANicenameGetsTheFallback() throws Exception {
        String name = "李小龙" + UUID.randomUUID();
        ImportedUser imported = asSystem(NewUser.of(name.substring(0, 3), "Lee"));
        assertThat(imported.profile().nicename())
                .isEqualTo("user-" + imported.profile().id().value());
    }

    @Test
    void importedNamesArePlainTextAsWordPressStoresThem() throws Exception {
        String tagged = ImportUsers.word();
        ImportedUser bob = asSystem(NewUser.of(tagged, "<script>alert(1)</script>Bob"));
        assertThat(bob.profile().displayName()).isEqualTo("Bob");

        String derived = ImportUsers.word();
        ImportedUser jane = asSystem(new NewUser(
                derived,
                Optional.empty(),
                "<b></b>",
                Optional.of("<i>Jane</i>"),
                Optional.of("Roe\n"),
                Optional.empty()));
        assertThat(jane.profile().displayName()).isEqualTo("Jane Roe");
        assertThat(provisioner.callsFor(derived).get(0).user().firstName()).contains("Jane");
    }

    @Test
    void anIdentityProviderUserThatAlreadyExistedGetsAProfileWithoutTheImportersEmail() throws Exception {
        String name = ImportUsers.word();
        String subject = "existing-" + UUID.randomUUID();
        provisioner.existing(name, subject);

        ImportedUser imported = asSystem(ImportUsers.john(name));

        assertThat(imported.createdInIdentityProvider()).isFalse();
        assertThat(imported.profileCreated()).isTrue();
        assertThat(imported.profile().subject()).isEqualTo(subject);
        assertThat(imported.profile().email()).isEmpty();
        assertThat(imported.profile().displayName()).isEqualTo("John Doe");
    }
}
