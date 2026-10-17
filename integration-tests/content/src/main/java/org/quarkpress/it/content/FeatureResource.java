package org.quarkpress.it.content;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.quarkpress.core.FeatureValue;
import org.quarkpress.core.SiteContext;
import org.quarkpress.persistence.FeatureToggles;

@Path("/it/features")
@Produces(MediaType.APPLICATION_JSON)
public class FeatureResource {

    @Inject
    SiteContext context;

    @Inject
    FeatureToggles toggles;

    @GET
    @Path("/{key}")
    public FeatureValue value(@PathParam("key") String key) {
        return context.features().value(key);
    }

    @PUT
    @Path("/{key}/{enabled}")
    public void set(@PathParam("key") String key, @PathParam("enabled") boolean enabled) {
        toggles.setForSite(key, enabled);
    }

    @DELETE
    @Path("/{key}")
    public void clear(@PathParam("key") String key) {
        toggles.clearForSite(key);
    }

    @PUT
    @Path("/network/{key}/{enabled}")
    public void setNetworkDefault(@PathParam("key") String key, @PathParam("enabled") boolean enabled) {
        toggles.setNetworkDefault(key, enabled);
    }

    @DELETE
    @Path("/network/{key}")
    public void clearNetworkDefault(@PathParam("key") String key) {
        toggles.clearNetworkDefault(key);
    }
}
