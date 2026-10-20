package org.quarkpress.it.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.quarkpress.core.CapabilityDeniedException;

/**
 * A network capability the caller lacks answers as a refused {@code @PermissionsAllowed} does: 401 when nobody is
 * authenticated (with the bearer challenge Quarkus sends), 403 otherwise.
 */
@Provider
public class CapabilityDeniedMapper implements ExceptionMapper<CapabilityDeniedException> {

    @Inject
    SecurityIdentity identity;

    @Override
    public Response toResponse(CapabilityDeniedException exception) {
        if (identity.isAnonymous()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .header("WWW-Authenticate", "Bearer")
                    .build();
        }
        return Response.status(Response.Status.FORBIDDEN).build();
    }
}
