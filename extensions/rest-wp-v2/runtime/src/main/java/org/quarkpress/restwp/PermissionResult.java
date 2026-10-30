package org.quarkpress.restwp;

import java.util.Objects;

/** The answer of a {@link Permission}. */
public sealed interface PermissionResult {

    record Allowed() implements PermissionResult {}

    record Denied(RestError error) implements PermissionResult {

        public Denied {
            Objects.requireNonNull(error, "error");
        }
    }

    static PermissionResult allowed() {
        return new Allowed();
    }

    static PermissionResult denied(RestError error) {
        return new Denied(error);
    }

    /**
     * WordPress's answer to a false permission callback: rest_forbidden, "Sorry, you are not allowed to do that.",
     * status 401 for an anonymous caller, 403 otherwise.
     */
    static PermissionResult forbidden(RestRequest request) {
        return denied(RestError.of("rest_forbidden", "Sorry, you are not allowed to do that.", request.authCode()));
    }
}
