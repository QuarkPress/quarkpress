package org.quarkpress.persistence.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.Optional;

@ApplicationScoped
public class NetworkOptionRepository implements PanacheRepositoryBase<NetworkOptionEntity, String> {

    /** The network option {@code name}; names must already be normalized (see {@link OptionRepository}). */
    public Optional<NetworkOptionEntity> findOption(String name) {
        return findByIdOptional(StoredOptionNames.require(name));
    }

    /**
     * The network option {@code name}, locked for update (PESSIMISTIC_WRITE) until the transaction ends, with its
     * state read from the database even when the row was already loaded in this transaction.
     */
    public Optional<NetworkOptionEntity> findOptionForUpdate(String name) {
        Optional<NetworkOptionEntity> option =
                findByIdOptional(StoredOptionNames.require(name), LockModeType.PESSIMISTIC_WRITE);
        option.ifPresent(getEntityManager()::refresh);
        return option;
    }
}
