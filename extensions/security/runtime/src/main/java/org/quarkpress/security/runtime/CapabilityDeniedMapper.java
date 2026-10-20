package org.quarkpress.security.runtime;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import org.quarkpress.core.CapabilityDeniedException;

/**
 * Answers a network capability the caller lacks as a refused {@code @PermissionsAllowed} does: 401 with the bearer
 * challenge for an anonymous caller, 403 otherwise, both with an empty body. Registered by the extension only when
 * Quarkus REST is present (it is no {@code @Provider}, and JAX-RS is an optional dependency); an application's own
 * mapper of this exception at the default priority takes precedence.
 */
public class CapabilityDeniedMapper implements ExceptionMapper<CapabilityDeniedException> {

    @Override
    public Response toResponse(CapabilityDeniedException exception) {
        if (exception.anonymous()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .build();
        }
        return Response.status(Response.Status.FORBIDDEN).build();
    }
}
