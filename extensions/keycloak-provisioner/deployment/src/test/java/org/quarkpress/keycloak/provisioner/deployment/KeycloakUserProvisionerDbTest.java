package org.quarkpress.keycloak.provisioner.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.quarkpress.security.ImportedUser;
import org.quarkpress.security.NewUser;
import org.quarkpress.security.PasswordHash;
import org.quarkpress.security.ProvisionedIdentity;
import org.quarkpress.security.SystemIdentity;
import org.quarkpress.security.UserImports;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.security.UserProvisioner;
import org.quarkpress.security.UserProvisioningException;
import org.quarkpress.security.UserProvisioningException.Reason;

/**
 * The Keycloak provisioner against Dev Services' Keycloak and the dev realm, without the WordPress hash provider: users
 * are created as asked, a hash is stored as a {@code wordpress} credential, an existing username is returned unchanged,
 * and Keycloak's refusals become fixed reasons.
 */
class KeycloakUserProvisionerDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            ProvisionerApp.app(KeycloakUserProvisionerDbTest.class, KeycloakReads.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    UserProvisioner provisioner;

    @Inject
    UserImports imports;

    @Inject
    SystemIdentity system;

    @Inject
    UserProfiles profiles;

    @Inject
    KeycloakReads keycloak;

    private UserRepresentation user(String subject) {
        return keycloak.user(subject);
    }

    private List<CredentialRepresentation> credentials(String subject) {
        return keycloak.credentials(subject);
    }

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    @Test
    void aUserWithoutAHashIsCreatedWithItsFieldsAndNoCredential() {
        String name = ProvisionerApp.username();
        ProvisionedIdentity identity = provisioner.provision(ProvisionerApp.john(name));

        assertThat(identity.created()).isTrue();
        UserRepresentation created = user(identity.subject());
        assertThat(created.getId()).isEqualTo(identity.subject());
        assertThat(created.getUsername()).isEqualTo(name);
        assertThat(created.getEmail()).isEqualTo(name + "@example.com");
        assertThat(created.isEmailVerified()).isTrue();
        assertThat(created.getFirstName()).isEqualTo("John");
        assertThat(created.getLastName()).isEqualTo("Doe");
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.getRequiredActions()).isEmpty();
        assertThat(credentials(identity.subject())).isEmpty();
    }

    @Test
    void aWordPressHashIsStoredAsAWordPressCredentialWhoseSecretIsNotReadable() throws Exception {
        String name = ProvisionerApp.username();
        ProvisionedIdentity identity = provisioner.provision(ProvisionerApp.john(name, ProvisionerApp.PHPASS));

        assertThat(identity.created()).isTrue();
        List<CredentialRepresentation> stored = credentials(identity.subject());
        assertThat(stored).hasSize(1);
        CredentialRepresentation credential = stored.get(0);
        assertThat(credential.getType()).isEqualTo("password");
        JsonNode data = json(credential.getCredentialData());
        assertThat(data.path("algorithm").asText()).isEqualTo("wordpress");
        // what makes Keycloak re-hash the password on the first successful sign-in
        assertThat(data.path("hashIterations").asInt(-1)).isZero();
        assertThat(credential.getSecretData()).isNull();
        assertThat(credential.getCredentialData()).doesNotContain(ProvisionerApp.PHPASS);
        // a permanent credential: no password update is asked at the first sign-in
        UserRepresentation created = user(identity.subject());
        assertThat(created.getRequiredActions()).isEmpty();
        assertThat(created.isEnabled()).isTrue();
    }

    @Test
    void theSameUsernameAgainReturnsTheExistingUserAndKeepsItsCredential() throws Exception {
        String name = ProvisionerApp.username();
        ProvisionedIdentity first = provisioner.provision(ProvisionerApp.john(name, ProvisionerApp.PHPASS));
        CredentialRepresentation before = credentials(first.subject()).get(0);

        ProvisionedIdentity again = provisioner.provision(new NewUser(
                name,
                Optional.of("other-" + name + "@example.com"),
                "Somebody Else",
                Optional.of("Somebody"),
                Optional.of("Else"),
                Optional.of(PasswordHash.wordpress(ProvisionerApp.PHPASS_OTHER))));

        assertThat(again.created()).isFalse();
        assertThat(again.subject()).isEqualTo(first.subject());
        List<CredentialRepresentation> after = credentials(first.subject());
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getId()).isEqualTo(before.getId());
        assertThat(after.get(0).getCreatedDate()).isEqualTo(before.getCreatedDate());
        assertThat(after.get(0).getCredentialData()).isEqualTo(before.getCredentialData());
        UserRepresentation kept = user(first.subject());
        assertThat(kept.getEmail()).isEqualTo(name + "@example.com");
        assertThat(kept.getFirstName()).isEqualTo("John");
        assertThat(keycloak.byUsername(name)).hasSize(1);
    }

    @Test
    void anotherUsernameWithATakenEmailIsRefused() {
        String name = ProvisionerApp.username();
        provisioner.provision(ProvisionerApp.john(name));
        String other = ProvisionerApp.username();

        assertThatThrownBy(() -> provisioner.provision(new NewUser(
                        other,
                        Optional.of(name + "@example.com"),
                        "Other",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())))
                .isInstanceOfSatisfying(UserProvisioningException.class, refused -> {
                    assertThat(refused.reason()).isEqualTo(Reason.EMAIL_TAKEN);
                    assertThat(refused.getMessage()).doesNotContain(name).doesNotContain(other);
                });
        assertThat(keycloak.byUsername(other)).isEmpty();
    }

    @Test
    void aUsernameWithASpaceIsRefusedAsKeycloakRefusesIt() {
        String name = "John " + ProvisionerApp.username();
        assertThatThrownBy(() -> provisioner.provision(NewUser.of(name, "John")))
                .isInstanceOfSatisfying(UserProvisioningException.class, refused -> {
                    assertThat(refused.reason()).isEqualTo(Reason.INVALID_USER);
                    assertThat(refused.getMessage())
                            .contains("error-username-invalid-character")
                            .doesNotContain(name);
                });
    }

    @Test
    void aUsernameUnderThreeCharactersIsRefusedAsKeycloakRefusesIt() {
        assertThatThrownBy(() -> provisioner.provision(NewUser.of("ab", "ab")))
                .isInstanceOfSatisfying(UserProvisioningException.class, refused -> {
                    assertThat(refused.reason()).isEqualTo(Reason.INVALID_USER);
                    assertThat(refused.getMessage()).contains("error-invalid-length");
                });
    }

    @Test
    void anUpperCaseUsernameIsStoredLowerCasedAndFoundAgainByItsLowerCaseForm() {
        String lower = ProvisionerApp.username();
        String mixed = "J" + lower;
        ProvisionedIdentity created = provisioner.provision(NewUser.of(mixed, "J"));

        assertThat(created.created()).isTrue();
        assertThat(user(created.subject()).getUsername()).isEqualTo("j" + lower);

        ProvisionedIdentity again = provisioner.provision(NewUser.of("j" + lower, "J"));
        assertThat(again.created()).isFalse();
        assertThat(again.subject()).isEqualTo(created.subject());
    }

    @Test
    void aUserWithoutNamesOrEmailIsCreated() {
        String name = ProvisionerApp.username();
        ProvisionedIdentity identity = provisioner.provision(NewUser.of(name, ""));

        assertThat(identity.created()).isTrue();
        UserRepresentation created = user(identity.subject());
        assertThat(created.getUsername()).isEqualTo(name);
        assertThat(created.getEmail()).isNull();
        assertThat(created.getFirstName()).isNull();
        assertThat(created.getLastName()).isNull();
    }

    @Test
    void anImportCreatesTheKeycloakUserAndTheProfileOfItsSubject() throws Exception {
        String name = ProvisionerApp.username();
        ImportedUser imported = system.call(() -> imports.importUser(ProvisionerApp.john(name, ProvisionerApp.PHPASS)));

        assertThat(imported.createdInIdentityProvider()).isTrue();
        assertThat(imported.profileCreated()).isTrue();
        String subject = imported.profile().subject();
        assertThat(user(subject).getUsername()).isEqualTo(name);
        assertThat(profiles.findBySubject(subject)).contains(imported.profile());
        assertThat(imported.profile().nicename()).isEqualTo(name);
        assertThat(imported.profile().displayName()).isEqualTo("John Doe");
    }

    @Test
    void aKeycloakUserWithoutAProfileGetsOneWhenTheImportIsRetried() throws Exception {
        // as if the profile insert had failed after the Keycloak user was created
        String name = ProvisionerApp.username();
        ProvisionedIdentity created = provisioner.provision(ProvisionerApp.john(name, ProvisionerApp.PHPASS));
        assertThat(profiles.findBySubject(created.subject())).isEmpty();

        ImportedUser retried = system.call(() -> imports.importUser(ProvisionerApp.john(name, ProvisionerApp.PHPASS)));

        assertThat(retried.createdInIdentityProvider()).isFalse();
        assertThat(retried.profileCreated()).isTrue();
        assertThat(retried.profile().subject()).isEqualTo(created.subject());
        // the Keycloak user existed for this call, so the profile waits for the e-mail of the first sign-in
        assertThat(retried.profile().email()).isEmpty();
        assertThat(keycloak.byUsername(name)).hasSize(1);
        assertThat(credentials(created.subject())).hasSize(1);

        ImportedUser third = system.call(() -> imports.importUser(ProvisionerApp.john(name, ProvisionerApp.PHPASS)));
        assertThat(third.createdInIdentityProvider()).isFalse();
        assertThat(third.profileCreated()).isFalse();
        assertThat(third.profile()).isEqualTo(retried.profile());
    }
}
