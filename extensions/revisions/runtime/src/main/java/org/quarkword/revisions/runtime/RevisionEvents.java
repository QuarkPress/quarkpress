package org.quarkword.revisions.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import org.quarkword.core.PostId;
import org.quarkword.core.RevisionId;
import org.quarkword.core.SiteId;
import org.quarkword.persistence.runtime.OptionTransactions;
import org.quarkword.revisions.AutosaveSaved;
import org.quarkword.revisions.RevisionCreated;
import org.quarkword.revisions.RevisionDeleted;
import org.quarkword.revisions.RevisionRestored;
import org.quarkword.revisions.RevisionSnapshot;

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
