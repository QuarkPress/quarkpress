package org.quarkword.security;

import java.util.Objects;

/**
 * A {@link UserProvisioner} could not create or find the user. The message is a fixed text that names the reason; it
 * never carries the request, the user's fields or a password hash.
 */
public final class UserProvisioningException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why provisioning failed. */
    public enum Reason {
        /** No identity-provider module provisions users. */
        NOT_CONFIGURED,
        /** Another identity-provider user has the e-mail address (WordPress's {@code existing_user_email}). */
        EMAIL_TAKEN,
        /** The identity provider refused the user's fields, such as a username it does not allow. */
        INVALID_USER,
        /** The identity provider refused QuarkWord's administrative credentials. */
        UNAUTHORIZED,
        /** The identity provider could not be reached or failed; the call may be retried. */
        UNAVAILABLE
    }

    private final Reason reason;

    public UserProvisioningException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public UserProvisioningException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
