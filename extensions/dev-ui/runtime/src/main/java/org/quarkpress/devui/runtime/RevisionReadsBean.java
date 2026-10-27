package org.quarkpress.devui.runtime;

import jakarta.inject.Inject;
import org.quarkpress.revisions.Revisions;

/** The content reads of the revisions module, through its service, as the caller (the Dev UI identity). */
public class RevisionReadsBean implements RevisionReads {

    @Inject
    Revisions revisions;

    @Override
    public long count() {
        return revisions.countAll();
    }
}
