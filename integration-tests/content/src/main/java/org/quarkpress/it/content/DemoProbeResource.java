package org.quarkpress.it.content;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteFeatureDisabledException;

@Path("/it/demo-probe")
@Produces(MediaType.TEXT_PLAIN)
public class DemoProbeResource {

    @Inject
    Event<DemoPing> pings;

    @Inject
    DemoObserver observer;

    @Inject
    DemoFilter filter;

    @Inject
    DemoService service;

    @Inject
    SiteContext context;

    @GET
    @Path("/observer")
    public String observer() {
        int before = observer.calls();
        pings.fire(new DemoPing("ping"));
        return Integer.toString(observer.calls() - before);
    }

    @GET
    @Path("/filter")
    public String filter(@QueryParam("text") String text) {
        return filter.apply(text, () -> context.siteId());
    }

    @GET
    @Path("/service")
    public String service() {
        try {
            return service.hello();
        } catch (SiteFeatureDisabledException disabled) {
            return "disabled";
        }
    }

    /** Calls the gated service without catching: the application's exception mappers decide the answer. */
    @GET
    @Path("/service-uncaught")
    public String serviceUncaught() {
        return service.hello();
    }
}
