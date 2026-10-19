package org.quarkpress.security.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class NetworkAdminRepository implements PanacheRepositoryBase<NetworkAdminEntity, Long> {}
