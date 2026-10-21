package org.quarkpress.taxonomy.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.OptionTransactions;
import org.quarkpress.taxonomy.TermCreated;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.TermUpdated;

/**
 * Fires the taxonomy module's events. Synchronous observers run inside the transaction; one that throws marks it for
 * rollback whatever the exception type. Asynchronous observers run after commit.
 */
@ApplicationScoped
public class TermEvents {

    @Inject
    OptionTransactions transactions;

    @Inject
    Event<TermCreated> createdEvent;

    public void created(SiteId site, Instant at, TermSnapshot term) {
        transactions.publish(createdEvent, new TermCreated(site, at, term));
    }

    @Inject
    Event<TermUpdated> updatedEvent;

    public void updated(SiteId site, Instant at, TermSnapshot before, TermSnapshot after) {
        transactions.publish(updatedEvent, new TermUpdated(site, at, before, after));
    }
}
