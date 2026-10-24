package org.quarkpress.revisions.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.OptionTransactions;
import org.quarkpress.revisions.AutosaveSaved;
import org.quarkpress.revisions.RevisionCreated;
import org.quarkpress.revisions.RevisionDeleted;
import org.quarkpress.revisions.RevisionRestored;
import org.quarkpress.revisions.RevisionSnapshot;

/**
 * Fires the revisions module's events. Synchronous observers run inside the transaction; one that throws marks it for
 * rollback whatever the exception type. Asynchronous observers run after commit.
 */
@ApplicationScoped
public class RevisionEvents {

    @Inject
    OptionTransactions transactions;

    @Inject
    Event<RevisionCreated> createdEvent;

    @Inject
    Event<RevisionDeleted> deletedEvent;

    @Inject
    Event<RevisionRestored> restoredEvent;

    @Inject
    Event<AutosaveSaved> autosavedEvent;

    public void created(SiteId site, Instant at, RevisionSnapshot revision) {
        transactions.publish(createdEvent, new RevisionCreated(site, at, revision));
    }

    public void autosaved(SiteId site, Instant at, RevisionSnapshot autosave, boolean updated) {
        transactions.publish(autosavedEvent, new AutosaveSaved(site, at, autosave, updated));
    }

    public void deleted(SiteId site, Instant at, RevisionSnapshot revision) {
        transactions.publish(deletedEvent, new RevisionDeleted(site, at, revision));
    }

    public void restored(SiteId site, Instant at, PostId post, RevisionId revision) {
        transactions.publish(restoredEvent, new RevisionRestored(site, at, post, revision));
    }
}
