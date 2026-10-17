package org.quarkpress.it.tenancy;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/spike")
@Produces(MediaType.TEXT_PLAIN)
public class SpikeResource {

    @Inject
    SpikeScenarios scenarios;

    @GET
    @Path("/observe/unguarded-bulk-update")
    public String unguardedBulkUpdate() {
        return scenarios.unguardedBulkUpdate();
    }

    @GET
    @Path("/{criterion}")
    public Response run(@PathParam("criterion") String criterion) {
        try {
            scenarios.run(criterion);
            return Response.ok("ok").build();
        } catch (Throwable failure) {
            return Response.serverError()
                    .entity(criterion + " failed: " + describe(failure))
                    .build();
        }
    }

    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null && text.length() < 4000; t = t.getCause()) {
            text.append(t.getClass().getName())
                    .append(": ")
                    .append(t.getMessage())
                    .append(" | ");
        }
        return text.toString();
    }
}
