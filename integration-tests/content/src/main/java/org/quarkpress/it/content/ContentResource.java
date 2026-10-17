package org.quarkpress.it.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.OptionRepository;
import org.quarkpress.persistence.runtime.SiteEntity;
import org.quarkpress.persistence.runtime.SiteRepository;

@Path("/it")
@Produces(MediaType.APPLICATION_JSON)
public class ContentResource {

    @Inject
    SiteContext context;

    @Inject
    Options options;

    @Inject
    OptionStore store;

    @Inject
    SiteScope siteScope;

    @Inject
    NetworkScope networkScope;

    @Inject
    SiteRepository sites;

    @Inject
    OptionRepository optionRepository;

    @GET
    @Path("/site")
    public SiteView site() {
        SiteInfo site = context.site();
        return new SiteView(
                site.id().value(),
                site.domain(),
                site.path(),
                site.name(),
                site.status().name(),
                context.zone().getId());
    }

    @PUT
    @Path("/options/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Boolean> setOption(@PathParam("name") String name, JsonNode value) {
        return Map.of("changed", options.set(name, value));
    }

    @GET
    @Path("/options/{name}")
    public Response option(@PathParam("name") String name) {
        return context.options()
                .get(name)
                .map(value -> Response.ok(value).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/options/{name}")
    public Map<String, Boolean> deleteOption(@PathParam("name") String name) {
        return Map.of("deleted", options.delete(name));
    }

    @POST
    @Path("/sites/{label}")
    @Transactional
    public Map<String, Long> createSite(@PathParam("label") String label) {
        SiteEntity site = SiteEntity.create(
                label + "-" + UUID.randomUUID() + ".test",
                "/",
                label,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        sites.persist(site);
        return Map.of("id", site.id);
    }

    @GET
    @Path("/isolation/{a}/{b}")
    @Produces(MediaType.TEXT_PLAIN)
    public String isolation(@PathParam("a") long a, @PathParam("b") long b) throws Exception {
        SiteId siteA = SiteId.of(a);
        SiteId siteB = SiteId.of(b);
        String name = "iso-" + UUID.randomUUID();
        siteScope.run(siteA, () -> options.set(name, TextNode.valueOf("a")));
        boolean leaked = siteScope.call(
                siteB,
                () -> store.find(siteA, name).isPresent()
                        || context.options().get(name).isPresent());
        boolean own = siteScope.call(siteA, () -> store.find(siteA, name).isPresent());
        return !leaked && own ? "isolated" : "leaked=" + leaked + " own=" + own;
    }

    @GET
    @Path("/network-scope")
    @Produces(MediaType.TEXT_PLAIN)
    public String networkScope() {
        try {
            networkScope.run(() -> {});
            return "allowed";
        } catch (SecurityException refused) {
            return refused.getMessage() != null && refused.getMessage().contains("the network scope is reserved")
                    ? "refused"
                    : "refused otherwise: " + refused.getMessage();
        }
    }

    @GET
    @Path("/no-request-context")
    @Produces(MediaType.TEXT_PLAIN)
    public String noRequestContext() throws InterruptedException {
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                outcome.set(QuarkusTransaction.requiringNew().call(() -> optionRepository.count()));
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        });
        worker.start();
        worker.join();
        if (outcome.get() instanceof Throwable failure) {
            for (Throwable t = failure; t != null; t = t.getCause()) {
                if (t.getMessage() != null && t.getMessage().contains("no request context")) {
                    return "failed-closed";
                }
            }
            return "failed-otherwise:" + failure;
        }
        return "leaked:" + outcome.get();
    }
}
