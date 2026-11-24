package org.quarkword.persistence.deployment;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.quarkword.core.SiteId;
import org.quarkword.persistence.runtime.SiteEntity;
import org.quarkword.persistence.runtime.SiteRepository;

/** Creates sites with unique domains; the test database is shared by every test class of a run. */
@ApplicationScoped
public class TestSites {

    @Inject
    SiteRepository sites;

    public SiteId create(String label) {
        return Requests.call(() -> QuarkusTransaction.requiringNew().call(() -> {
            SiteEntity site = SiteEntity.create(
                    label + "-" + UUID.randomUUID() + ".test",
                    "/",
                    label,
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
            sites.persist(site);
            return SiteId.of(site.id);
        }));
    }
}
