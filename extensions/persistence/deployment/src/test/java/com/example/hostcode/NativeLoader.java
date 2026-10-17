package com.example.hostcode;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.quarkpress.persistence.runtime.OptionEntity;

@ApplicationScoped
public class NativeLoader {

    @Inject
    @PersistenceUnit("quarkpress")
    EntityManager em;

    /** Loads an option row with native SQL, which the site filter does not see, and changes it. */
    public void loadAroundTheFilterAndChange(long optionId, JsonNode value) {
        OptionEntity row =
                (OptionEntity) em.createNativeQuery("select * from qp_option where id = :id", OptionEntity.class)
                        .setParameter("id", optionId)
                        .getSingleResult();
        row.value = value;
        em.flush();
    }
}
