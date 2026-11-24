package org.quarkword.taxonomy.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.core.TermId;
import org.quarkword.persistence.runtime.OptionTransactions;
import org.quarkword.taxonomy.TermCreated;
import org.quarkword.taxonomy.TermDeleted;
import org.quarkword.taxonomy.TermSnapshot;
import org.quarkword.taxonomy.TermUpdated;
import org.quarkword.taxonomy.TermsAssigned;

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
