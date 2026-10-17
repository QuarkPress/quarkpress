package org.quarkpress.core.runtime;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Answers a disabled feature's REST resource with an empty 404. Loaded only when JAX-RS is present. */
@Provider
public class SiteFeatureNotFoundMapper implements ExceptionMapper<SiteFeatureNotFoundException> {

    @Override
    public Response toResponse(SiteFeatureNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
}
