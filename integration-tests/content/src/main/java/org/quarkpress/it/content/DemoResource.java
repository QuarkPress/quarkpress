package org.quarkpress.it.content;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.quarkpress.core.SiteFeature;

@Path("/it/demo")
@SiteFeature("demo")
public class DemoResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String get() {
        return "demo";
    }
}
