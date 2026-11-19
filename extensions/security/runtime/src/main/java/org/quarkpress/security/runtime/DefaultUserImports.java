package org.quarkpress.security.runtime;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.InvalidTransactionException;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.CapabilityDeniedException;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.ImportedUser;
import org.quarkpress.security.NewUser;
import org.quarkpress.security.ProvisionedIdentity;
import org.quarkpress.security.UserImports;
import org.quarkpress.security.UserProvisioner;

/**
 * Imports a user in three steps: the current identity must hold {@code create_users} for the binding in effect (the
 * system identity does; in a network, by default only network administrators do), then the {@link UserProvisioner}
 * is called with any transaction of the caller suspended, then {@link ProfileProvisioning} finds or creates the
 * profile of the subject it returns, in a transaction of its own (without the importer's e-mail when the identity
 * provider already had the user). Nothing is retried here: an import is idempotent by
 * username, so the caller retries it.
 */
@ApplicationScoped
public class DefaultUserImports implements UserImports {

    static final String CREATE_USERS = "create_users";

    @Inject
    Capabilities capabilities;

    @Inject
    UserProvisioner provisioner;

    @Inject
    ProfileProvisioning provisioning;

    @Inject
    TransactionManager transactions;

    @Override
    public ImportedUser importUser(NewUser user) {
        Objects.requireNonNull(user, "user");
        SecurityIdentity identity = JpaCapabilities.currentIdentity();
        if (!capabilities.can(identity, CREATE_USERS, Optional.empty())) {
            throw new CapabilityDeniedException(CREATE_USERS, identity == null || identity.isAnonymous());
        }
        ProvisionedIdentity provisioned = outsideTransaction(user);
        // an identity-provider user that existed keeps its own e-mail: its first login brings the verified one
        NewUser named = provisioned.created()
                ? user
                : new NewUser(
                        user.username(),
                        Optional.empty(),
                        user.displayName(),
                        user.firstName(),
                        user.lastName(),
                        user.password());
        ProfileProvisioning.Provisioned profile = provisioning.importProfile(provisioned.subject(), named);
        return new ImportedUser(profile.profile(), provisioned.created(), profile.created());
    }

    /** Calls the provisioner with the caller's transaction, if any, suspended; its exceptions pass unchanged. */
    private ProvisionedIdentity outsideTransaction(NewUser user) {
        Transaction suspended;
        try {
            suspended = transactions.suspend();
        } catch (SystemException e) {
            throw new IllegalStateException("cannot suspend the caller's transaction", e);
        }
        try {
            return Objects.requireNonNull(provisioner.provision(user), "the provisioner returned null");
        } finally {
            if (suspended != null) {
                try {
                    transactions.resume(suspended);
                } catch (InvalidTransactionException | SystemException e) {
                    throw new IllegalStateException("cannot resume the caller's transaction", e);
                }
            }
        }
    }
}
