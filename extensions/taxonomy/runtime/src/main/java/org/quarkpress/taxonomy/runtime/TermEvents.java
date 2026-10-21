package org.quarkpress.taxonomy.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.persistence.runtime.OptionTransactions;
import org.quarkpress.taxonomy.TermCreated;
import org.quarkpress.taxonomy.TermDeleted;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.TermUpdated;
import org.quarkpress.taxonomy.TermsAssigned;

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

    @Inject
    Event<TermUpdated> updatedEvent;

    @Inject
    Event<TermsAssigned> assignedEvent;

    @Inject
    Event<TermDeleted> deletedEvent;

    public void created(SiteId site, Instant at, TermSnapshot term) {
        transactions.publish(createdEvent, new TermCreated(site, at, term));
    }

    public void updated(SiteId site, Instant at, TermSnapshot before, TermSnapshot after) {
        transactions.publish(updatedEvent, new TermUpdated(site, at, before, after));
    }

    public void assigned(
            SiteId site, Instant at, PostId post, String taxonomy, List<TermId> before, List<TermId> after) {
        transactions.publish(assignedEvent, new TermsAssigned(site, at, post, taxonomy, before, after));
    }

    public void deleted(SiteId site, Instant at, TermSnapshot term) {
        transactions.publish(deletedEvent, new TermDeleted(site, at, term));
    }
}
