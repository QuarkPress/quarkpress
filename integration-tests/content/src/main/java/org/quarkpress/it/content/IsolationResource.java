package org.quarkpress.it.content;

import com.example.attempts.IsolationAttempts;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.quarkpress.persistence.runtime.SiteEntity;
import org.quarkpress.persistence.runtime.SiteRepository;

@Path("/it/isolation-check")
@Produces(MediaType.TEXT_PLAIN)
public class IsolationResource {

    @Inject
    IsolationAttempts attempts;

    @Inject
    SiteRepository sites;

    @GET
    @Path("/{criterion}")
    public String check(@PathParam("criterion") String criterion) throws Exception {
        return switch (criterion) {
            case "caches-off" -> attempts.cachesOff(newSite("cache-a"), newSite("cache-b"));
            case "collections-filtered" -> attempts.collectionsFiltered(newSite("shelf-a"), newSite("shelf-b"));
            case "loaded-site-guarded" -> attempts.loadedSiteGuarded(newSite("guard-a"), newSite("guard-b"));
            case "propagated-tasks-keep-their-site" -> attempts.propagatedTasksKeepTheirSite(newSite("prop-b"));
            case "unbound-writes-refused" -> attempts.unboundWritesRefused(newSite("unbound"));
            default -> throw new NotFoundException();
        };
    }

    private long newSite(String label) {
        return QuarkusTransaction.requiringNew().call(() -> {
            SiteEntity site = SiteEntity.create(
                    label + "-" + UUID.randomUUID() + ".test",
                    "/",
                    label,
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
            sites.persist(site);
            return site.id;
        });
    }
}
