package org.quarkpress.it.multisite;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.it.feature.DemoFeatures;

@Path("/it/demo")
@SiteFeature(DemoFeatures.DEMO)
public class DemoResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String get() {
        return "demo";
    }
}
