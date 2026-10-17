package org.quarkpress.persistence.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SiteRepository implements PanacheRepositoryBase<SiteEntity, Long> {}
