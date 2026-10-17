package org.quarkpress.it.content;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.quarkpress.core.SiteFeature;

/** Gated by a feature that is on unless the site or the network turns it off. */
@Path("/it/demo-on")
@SiteFeature("demo-on")
public class DemoOnResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String get() {
        return "demo-on";
    }
}
