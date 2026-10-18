package org.quarkpress.multisite.deployment;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.quarkpress.core.SiteId;
import org.quarkpress.multisite.runtime.SiteDomainEntity;
import org.quarkpress.multisite.runtime.SiteDomainRepository;
import org.quarkpress.persistence.runtime.SiteEntity;
import org.quarkpress.persistence.runtime.SiteRepository;

/** Writes sites and aliases directly, for tests of the resolution; the database is shared by the test classes. */
@ApplicationScoped
public class Network {

    @Inject
    SiteRepository sites;

    @Inject
    SiteDomainRepository domains;

    public static String unique(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public SiteId site(String domain, String path) {
        return Requests.call(() -> QuarkusTransaction.requiringNew().call(() -> {
            SiteEntity site = SiteEntity.create(domain, path, domain + path, now());
            sites.persist(site);
            return SiteId.of(site.id);
        }));
    }

    public void alias(SiteId site, String domain, boolean redirectToPrimary) {
        Requests.run(() -> QuarkusTransaction.requiringNew()
                .run(() -> domains.persist(SiteDomainEntity.create(site, domain, redirectToPrimary, now()))));
    }

    public void archive(SiteId site) {
        Requests.run(() -> QuarkusTransaction.requiringNew().run(() -> sites.findById(site.value()).archived = true));
    }

    public void moveMain(String domain, String path) {
        Requests.run(() -> QuarkusTransaction.requiringNew().run(() -> {
            SiteEntity main = sites.findById(1L);
            main.domain = domain;
            main.path = path;
        }));
    }
}
