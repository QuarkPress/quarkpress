package org.quarkword.security.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.quarkword.security.NewUser;
import org.quarkword.security.ProvisionedIdentity;
import org.quarkword.security.UserProvisioner;
import org.quarkword.security.UserProvisioningException;

/** The provisioner when no identity-provider module provides one: it refuses every call. */
@DefaultBean
@ApplicationScoped
public class NoUserProvisioner implements UserProvisioner {

    static final String MESSAGE =
            "No identity provider module provisions users; install quarkword-keycloak-provisioner.";

    @Override
    public ProvisionedIdentity provision(NewUser user) {
        throw new UserProvisioningException(UserProvisioningException.Reason.NOT_CONFIGURED, MESSAGE);
    }
}
