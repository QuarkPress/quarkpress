package org.quarkpress.security.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.quarkpress.security.NewUser;
import org.quarkpress.security.ProvisionedIdentity;
import org.quarkpress.security.UserProvisioner;
import org.quarkpress.security.UserProvisioningException;

/** The provisioner when no identity-provider module provides one: it refuses every call. */
@DefaultBean
@ApplicationScoped
public class NoUserProvisioner implements UserProvisioner {

    static final String MESSAGE =
            "No identity provider module provisions users; install quarkpress-keycloak-provisioner.";

    @Override
    public ProvisionedIdentity provision(NewUser user) {
        throw new UserProvisioningException(UserProvisioningException.Reason.NOT_CONFIGURED, MESSAGE);
    }
}
