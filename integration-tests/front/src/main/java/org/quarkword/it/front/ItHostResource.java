package org.quarkword.it.front;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** A resource of the host application beside the front end's catch-all route, which must win over it. */
@Path("/host")
public class ItHostResource {

    /** Answers {@code pong}. */
    @GET
    @Path("/ping")
    @Produces(MediaType.TEXT_PLAIN)
    public String ping() {
        return "pong";
    }
}
