package org.quarkpress.persistence.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class NetworkOptionRepository implements PanacheRepositoryBase<NetworkOptionEntity, String> {

    /** The network option {@code name}; names must already be normalized (see {@link OptionRepository}). */
    public Optional<NetworkOptionEntity> findOption(String name) {
        return findByIdOptional(StoredOptionNames.require(name));
    }
}
