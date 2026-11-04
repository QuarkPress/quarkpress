package org.quarkpress.restwp.runtime.wpv2;

import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.function.Supplier;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.revisions.RevisionCount;
import org.quarkpress.revisions.RevisionException;
import org.quarkpress.revisions.Revisions;
import org.quarkpress.security.SystemIdentity;

/**
 * The revision history of the post links, read from the revisions module as the system (the caller need not edit
 * the post to see the count, as WordPress's link carries it for every reader), and the revision of a REST update,
 * saved as the caller. A bean only while the revisions module is present.
 */
@ApplicationScoped
class RevisionHistoryReads implements RevisionHistory {

    @Inject
    Revisions revisions;

    @Inject
    SystemIdentity system;

    @Inject
    CurrentIdentityAssociation association;

    @Override
    public Count of(PostId post) {
        SecurityIdentity previous = association.getIdentity();
        association.setIdentity(system.identity());
        try {
            RevisionCount count = revisions.latestAndCount(post);
            return new Count(
                    count.count(), count.latest().map(RevisionId::value).orElse(0L));
        } catch (RevisionException off) {
            // revisions are off for the post: WordPress links a count of 0
            return new Count(0, 0);
        } finally {
            association.setIdentity(previous);
        }
    }

    @Override
    public <T> T revisingAfter(PostId post, Supplier<T> work) {
        return revisions.revisingAfter(post, work);
    }
}
