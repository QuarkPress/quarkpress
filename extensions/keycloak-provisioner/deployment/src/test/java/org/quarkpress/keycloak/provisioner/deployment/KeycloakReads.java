package org.quarkpress.keycloak.provisioner.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.List;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/** Reads users of the dev realm through the admin client, which is request-scoped, for the tests' checks. */
@ApplicationScoped
public class KeycloakReads {

    @Inject
    Keycloak keycloak;

    @ActivateRequestContext
    public UserRepresentation user(String subject) {
        return keycloak.realm(ProvisionerApp.REALM).users().get(subject).toRepresentation();
    }

    @ActivateRequestContext
    public List<CredentialRepresentation> credentials(String subject) {
        return keycloak.realm(ProvisionerApp.REALM).users().get(subject).credentials();
    }

    @ActivateRequestContext
    public List<UserRepresentation> byUsername(String username) {
        return keycloak.realm(ProvisionerApp.REALM).users().searchByUsername(username, true);
    }
}
