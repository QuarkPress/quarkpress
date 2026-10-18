package org.quarkpress.it.multisite;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.net.URI;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteInfo;

/** Answers every GET path the application does not serve otherwise with the request's site. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class ProbeResource {

    @Inject
    SiteContext context;

    @GET
    @Path("{path: .*}")
    public SiteProbe probe() {
        if (!context.isResolved()) {
            return new SiteProbe(false, null, null, null, null, null);
        }
        SiteInfo site = context.site();
        return new SiteProbe(
                true,
                site.id().value(),
                site.domain(),
                site.path(),
                site.status().name(),
                context.redirect().map(URI::toString).orElse(null));
    }
}
