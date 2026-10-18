package org.quarkpress.multisite.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.CapabilityCheck;
import org.quarkpress.core.NetworkCapabilities;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteId;
import org.quarkpress.multisite.SiteDomain;
import org.quarkpress.multisite.SiteDomains;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.persistence.runtime.SiteRepository;

/** Aliases stored in qp_site_domain; writes take the network lock first, like every network write. */
@ApplicationScoped
public class JpaSiteDomains implements SiteDomains {

    @Inject
    NetworkScope network;

    @Inject
    NetworkLocks locks;

    @Inject
    SiteRepository sites;

    @Inject
    SiteDomainRepository domains;

    @Inject
    CapabilityCheck capabilities;

    @Inject
    QuarkPressClock clock;

    @Override
    public SiteDomain add(SiteId site, String domain, boolean redirectToPrimary) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(domain, "domain");
        capabilities.requireNetwork(NetworkCapabilities.MANAGE_SITES);
        String normalized = SiteAddresses.normalizeDomain(domain);
        if (normalized.equals("0")) {
            throw new IllegalArgumentException("a site domain must not be empty");
        }
        return NetworkWork.call(network, () -> {
            locks.network();
            if (sites.findByIdOptional(site.value()).isEmpty()) {
                throw new IllegalArgumentException("site " + site.value() + " does not exist");
            }
            // every lookup also tries a www. host without its prefix: no form of the alias may be a site's domain
            if (sites.count("domain in ?1", SiteAddresses.wwwForms(normalized)) > 0) {
                throw new IllegalArgumentException("the domain " + normalized + " is the address of a site");
            }
            Optional<SiteDomainEntity> existing = domains.findByDomain(normalized);
            if (existing.isPresent()) {
                throw new IllegalArgumentException(
                        "the domain " + normalized + " is already an alias of site " + existing.get().siteId);
            }
            SiteDomainEntity entity = SiteDomainEntity.create(site, normalized, redirectToPrimary, now());
            domains.persist(entity);
            return entity.toDomain();
        });
    }

    @Override
    public boolean remove(String domain) {
        Objects.requireNonNull(domain, "domain");
        capabilities.requireNetwork(NetworkCapabilities.MANAGE_SITES);
        String normalized;
        try {
            normalized = SiteAddresses.normalizeDomain(domain);
        } catch (IllegalArgumentException notADomain) {
            // no alias can have it, as find answers
            return false;
        }
        return NetworkWork.call(network, () -> {
            locks.network();
            Optional<SiteDomainEntity> existing = domains.findByDomain(normalized);
            existing.ifPresent(domains::delete);
            return existing.isPresent();
        });
    }

    @Override
    @ActivateRequestContext
    @Transactional
    public Optional<SiteDomain> find(String domain) {
        String normalized;
        try {
            normalized = SiteAddresses.normalizeDomain(domain);
        } catch (IllegalArgumentException notADomain) {
            return Optional.empty();
        }
        return domains.findByDomain(normalized).map(SiteDomainEntity::toDomain);
    }

    @Override
    @ActivateRequestContext
    @Transactional
    public List<SiteDomain> forSite(SiteId site) {
        Objects.requireNonNull(site, "site");
        return domains.forSite(site.value()).stream()
                .map(SiteDomainEntity::toDomain)
                .toList();
    }

    private Instant now() {
        return clock.now().truncatedTo(ChronoUnit.MICROS);
    }
}
